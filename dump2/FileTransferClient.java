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

public class FileTransferClient {
    private final InetSocketAddress[] endpoints;
    private final int channels;

    /**
     * @param endpoints array of remote addresses (host+port) to use for channels
     * @param channels number of total parallel channels to open
     */
    public FileTransferClient(InetSocketAddress[] endpoints, int channels) {
        this.endpoints = endpoints;
        this.channels = channels;
    }

    public void sendFile(File f) throws Exception {
        long size = f.length();
        long partSize = (size + channels -1) / channels;
        ExecutorService pool = Executors.newFixedThreadPool(channels);
        for (int i=0;i<channels;i++) {
            long offset = i * partSize;
            long len = Math.min(partSize, size - offset);
            if (len<=0) break;
            int part = i;
            InetSocketAddress endpoint = endpoints[i % endpoints.length];
            pool.submit(() -> sendPart(endpoint, f, offset, len, part, channels));
        }
        pool.shutdown();
        pool.awaitTermination(1, TimeUnit.HOURS);
    }

    private void sendPart(InetSocketAddress endpoint, File f, long offset, long len, int part, int partCount) {
        try (Socket s = new Socket()) {
            s.connect(endpoint);
            try (DataOutputStream out = new DataOutputStream(s.getOutputStream());
                 RandomAccessFile raf = new RandomAccessFile(f,"r")) {
                out.writeUTF(f.getName());
                out.writeLong(f.length());
                out.writeInt(part);
                out.writeInt(partCount);
                out.writeLong(offset);
                out.writeLong(len);
                raf.seek(offset);
                byte[] buf = new byte[8192];
                long rem = len;
                while (rem>0) {
                    int r = raf.read(buf,0,(int)Math.min(buf.length,rem));
                    if (r<0) break;
                    out.write(buf,0,r);
                    rem -= r;
                }
                System.out.println("sent part "+part+" via "+endpoint);
            }
        } catch (IOException e) {
            System.err.println("failed part " + part + " to " + endpoint + ": " + e);
            e.printStackTrace();
        }
    }

    // small CLI for testing and usb+wifi support
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Usage: java FileTransferClient <host[:port],...> <channels> <file>");
            System.out.println("Example: java FileTransferClient 127.0.0.1:5000,192.168.0.100:5000 4 myfile.bin");
            System.out.println("Use adb forward for USB: `adb forward tcp:5000 tcp:5000` then include 127.0.0.1:5000 as one endpoint");
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
            String[] hp = p.split(":");
            if (hp.length == 0) continue;
            String host = hp[0];
            int port = hp.length > 1 ? Integer.parseInt(hp[1]) : 5000;
            list.add(new InetSocketAddress(host, port));
        }
        return list.toArray(new InetSocketAddress[0]);
    }
}
