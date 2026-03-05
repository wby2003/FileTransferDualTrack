import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class FileTransferClient {
    private static final int DEFAULT_BLOCK_SIZE = 1024 * 1024;
    private static final int PROBE_SIZE_BYTES = 4 * 1024 * 1024;
    private static final String PROBE_FILE_PREFIX = "__PROBE__";
    private final InetSocketAddress[] endpoints;
    private final int channels;
    private final int blockSize;

    /**
     * @param endpoints array of remote addresses (host+port) to use for channels
     * @param channels number of total parallel channels to open
     */
    public FileTransferClient(InetSocketAddress[] endpoints, int channels) {
        this(endpoints, channels, DEFAULT_BLOCK_SIZE);
    }

    public FileTransferClient(InetSocketAddress[] endpoints, int channels, int blockSize) {
        this.endpoints = endpoints;
        this.channels = channels;
        this.blockSize = blockSize;
    }

    public void sendFile(File f) throws Exception {
        long size = f.length();
        int totalBlocks = (int) ((size + blockSize - 1L) / blockSize);
        AtomicInteger nextBlock = new AtomicInteger(0);
        AtomicInteger nextEndpoint = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(channels);
        for (int i = 0; i < channels; i++) {
            pool.submit(() -> {
                while (true) {
                    int blockIndex = nextBlock.getAndIncrement();
                    if (blockIndex >= totalBlocks) {
                        break;
                    }
                    InetSocketAddress endpoint = endpoints[Math.floorMod(nextEndpoint.getAndIncrement(), endpoints.length)];
                    long offset = (long) blockIndex * blockSize;
                    long len = Math.min(blockSize, size - offset);
                    sendBlock(endpoint, f, offset, len, blockIndex, totalBlocks);
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(1, TimeUnit.HOURS);
    }

    private void sendBlock(InetSocketAddress endpoint, File f, long offset, long len, int blockIndex, int totalBlocks) {
        try (Socket s = new Socket()) {
            s.connect(endpoint);
            try (DataOutputStream out = new DataOutputStream(s.getOutputStream());
                 RandomAccessFile raf = new RandomAccessFile(f,"r")) {
                out.writeUTF(f.getName());
                out.writeLong(f.length());
                out.writeInt(blockIndex);
                out.writeInt(totalBlocks);
                out.writeLong(offset);
                out.writeLong(len);
                raf.seek(offset);
                byte[] buf = new byte[8192];
                long rem = len;
                while (rem > 0) {
                    int r = raf.read(buf, 0, (int) Math.min(buf.length, rem));
                    if (r<0) break;
                    out.write(buf,0,r);
                    rem -= r;
                }
                System.out.println("sent block " + blockIndex + " via " + endpoint);
            }
        } catch (IOException e) {
            System.err.println("failed block " + blockIndex + " to " + endpoint + ": " + e);
            e.printStackTrace();
        }
    }

    // small CLI for testing and usb+wifi support
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Usage: java FileTransferClient <host[:port],...> <channels> <file>");
            System.out.println("Example: java FileTransferClient 127.0.0.1:5000@3,192.168.0.100:5000@1 4 myfile.bin");
            System.out.println("Use adb forward for USB: `adb forward tcp:5000 tcp:5000` then include 127.0.0.1:5000 as one endpoint");
            System.out.println("Optional weight syntax: host:port@weight, e.g. USB@3 and WiFi@1");
            System.out.println("Auto mode: append --auto, e.g. java FileTransferClient 127.0.0.1:5002,192.168.31.206:5002 4 perf100.bin --auto");
            return;
        }

        boolean auto = Arrays.asList(args).contains("--auto");
        List<EndpointSpec> specs = parseEndpointSpecs(args[0]);
        int ch = Integer.parseInt(args[1]);
        File f = new File(args[2]);

        if (auto) {
            autoAssignWeights(specs, ch);
        }
        InetSocketAddress[] endpoints = expandEndpoints(specs);

        new FileTransferClient(endpoints,ch).sendFile(f);
    }

    private static List<EndpointSpec> parseEndpointSpecs(String s) throws IllegalArgumentException {
        String[] parts = s.split(",");
        List<EndpointSpec> list = new ArrayList<>();
        for (String p : parts) {
            String endpointPart = p.trim();
            if (endpointPart.isEmpty()) {
                continue;
            }

            int weight = 1;
            int weightSep = endpointPart.lastIndexOf('@');
            if (weightSep > 0 && weightSep < endpointPart.length() - 1) {
                weight = Math.max(1, Integer.parseInt(endpointPart.substring(weightSep + 1)));
                endpointPart = endpointPart.substring(0, weightSep);
            }

            String[] hp = endpointPart.split(":");
            if (hp.length == 0) continue;
            String host = hp[0];
            int port = hp.length > 1 ? Integer.parseInt(hp[1]) : 5000;
            list.add(new EndpointSpec(new InetSocketAddress(host, port), weight));
        }
        return list;
    }

    private static InetSocketAddress[] expandEndpoints(List<EndpointSpec> specs) {
        List<InetSocketAddress> endpoints = new ArrayList<>();
        for (EndpointSpec spec : specs) {
            for (int i = 0; i < spec.weight; i++) {
                endpoints.add(spec.endpoint);
            }
        }
        return endpoints.toArray(new InetSocketAddress[0]);
    }

    private static void autoAssignWeights(List<EndpointSpec> specs, int channels) {
        System.out.println("auto probing endpoints...");
        double[] speeds = new double[specs.size()];
        double sum = 0;

        for (int i = 0; i < specs.size(); i++) {
            EndpointSpec spec = specs.get(i);
            speeds[i] = probeEndpointMBps(spec.endpoint);
            if (speeds[i] <= 0) {
                speeds[i] = 1.0;
            }
            sum += speeds[i];
        }

        int targetWeightSum = Math.max(channels, specs.size());
        int currentSum = 0;
        for (int i = 0; i < specs.size(); i++) {
            EndpointSpec spec = specs.get(i);
            int w = (int) Math.round(speeds[i] / sum * targetWeightSum);
            spec.weight = Math.max(1, w);
            currentSum += spec.weight;
        }

        while (currentSum < targetWeightSum) {
            int idx = indexOfMax(speeds);
            specs.get(idx).weight++;
            currentSum++;
        }
        while (currentSum > targetWeightSum) {
            int idx = indexOfWeightiest(specs);
            if (specs.get(idx).weight <= 1) {
                break;
            }
            specs.get(idx).weight--;
            currentSum--;
        }

        for (int i = 0; i < specs.size(); i++) {
            EndpointSpec spec = specs.get(i);
            System.out.printf("auto weight %s => speed=%.2f MB/s, weight=%d%n",
                    spec.endpoint, speeds[i], spec.weight);
        }
    }

    private static int indexOfMax(double[] arr) {
        int idx = 0;
        for (int i = 1; i < arr.length; i++) {
            if (arr[i] > arr[idx]) {
                idx = i;
            }
        }
        return idx;
    }

    private static int indexOfWeightiest(List<EndpointSpec> specs) {
        int idx = 0;
        for (int i = 1; i < specs.size(); i++) {
            if (specs.get(i).weight > specs.get(idx).weight) {
                idx = i;
            }
        }
        return idx;
    }

    private static double probeEndpointMBps(InetSocketAddress endpoint) {
        byte[] probeData = new byte[PROBE_SIZE_BYTES];
        long start = System.nanoTime();
        try (Socket s = new Socket()) {
            s.connect(endpoint);
            try (DataOutputStream out = new DataOutputStream(s.getOutputStream())) {
                out.writeUTF(PROBE_FILE_PREFIX + System.nanoTime());
                out.writeLong(PROBE_SIZE_BYTES);
                out.writeInt(0);
                out.writeInt(1);
                out.writeLong(0);
                out.writeLong(PROBE_SIZE_BYTES);
                out.write(probeData);
                out.flush();
            }
        } catch (IOException e) {
            System.err.println("probe failed for " + endpoint + ": " + e.getMessage());
            return 0;
        }
        double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
        if (seconds <= 0) {
            return 0;
        }
        return (PROBE_SIZE_BYTES / 1024.0 / 1024.0) / seconds;
    }

    private static class EndpointSpec {
        private final InetSocketAddress endpoint;
        private int weight;

        private EndpointSpec(InetSocketAddress endpoint, int weight) {
            this.endpoint = endpoint;
            this.weight = weight;
        }
    }
}
