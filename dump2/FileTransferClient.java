import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class FileTransferClient {
    private static final int DEFAULT_BLOCK_SIZE = 1024 * 1024;
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

        ExecutorService pool = Executors.newFixedThreadPool(channels);
        for (int i = 0; i < channels; i++) {
            InetSocketAddress endpoint = endpoints[i % endpoints.length];
            pool.submit(() -> {
                while (true) {
                    int blockIndex = nextBlock.getAndIncrement();
                    if (blockIndex >= totalBlocks) {
                        break;
                    }
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
            return;
        }
        InetSocketAddress[] endpoints = parseEndpoints(args[0]);
        int ch = Integer.parseInt(args[1]);
        File f = new File(args[2]);
        new FileTransferClient(endpoints,ch).sendFile(f);
    }

    private static InetSocketAddress[] parseEndpoints(String s) throws IllegalArgumentException {
        String[] parts = s.split(",");
        List<InetSocketAddress> list = new ArrayList<>();
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
            for (int i = 0; i < weight; i++) {
                list.add(new InetSocketAddress(host, port));
            }
        }
        return list.toArray(new InetSocketAddress[0]);
    }
}
