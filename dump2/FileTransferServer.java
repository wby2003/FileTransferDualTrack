import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class FileTransferServer {
    private static final String PROBE_FILE_PREFIX = "__PROBE__";
    private static final int ACK_REQUEST_PART_INDEX = -2;
    private final int port;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final ConcurrentMap<String, AtomicInteger> blocksToFinish = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> fileLocks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CountDownLatch> transferDoneSignals = new ConcurrentHashMap<>();
    private final File outputDir;

    public FileTransferServer(int port, File outputDir) {
        this.port = port;
        this.outputDir = outputDir;
    }

    public void start() throws IOException {
        ServerSocket srv = new ServerSocket(port);
        System.out.println("server listening on " + port);
        while (true) {
            Socket sock = srv.accept();
            pool.execute(new Handler(sock));
        }
    }

    private class Handler implements Runnable {
        private final Socket s;
        Handler(Socket s) {
            this.s = s;
        }
        @Override
        public void run() {
            try (DataInputStream in = new DataInputStream(s.getInputStream())) {
                while (true) {
                    // block header: filename, total size, transfer id, block index, block count, offset, length
                    String name;
                    try {
                        name = in.readUTF();
                    } catch (EOFException endOfStream) {
                        break;
                    }
                    long totalSize = in.readLong();
                    long transferId = in.readLong();
                    int part = in.readInt();
                    int partCount = in.readInt();
                    long offset = in.readLong();
                    long len = in.readLong();

                    String transferKey = name + "#" + transferId;

                    if (part == ACK_REQUEST_PART_INDEX) {
                        CountDownLatch doneSignal = transferDoneSignals.computeIfAbsent(transferKey, k -> new CountDownLatch(1));
                        boolean done = doneSignal.await(120, TimeUnit.SECONDS);
                        s.getOutputStream().write(done ? 1 : 0);
                        s.getOutputStream().flush();
                        if (done) {
                            transferDoneSignals.remove(transferKey, doneSignal);
                        }
                        break;
                    }

                    if (name.startsWith(PROBE_FILE_PREFIX)) {
                        byte[] probeBuf = new byte[8192];
                        long remainingProbe = len;
                        while (remainingProbe > 0) {
                            int r = in.read(probeBuf, 0, (int) Math.min(probeBuf.length, remainingProbe));
                            if (r < 0) {
                                throw new EOFException();
                            }
                            remainingProbe -= r;
                        }
                        continue;
                    }

                    File out = new File(outputDir, name);
                    final boolean[] createdCounter = new boolean[] {false};
                    AtomicInteger counter = blocksToFinish.compute(transferKey, (k, v) -> {
                        if (v == null || v.get() <= 0) {
                            createdCounter[0] = true;
                            return new AtomicInteger(partCount);
                        }
                        return v;
                    });
                    Object lock = fileLocks.computeIfAbsent(transferKey, k -> new Object());

                    System.out.printf("recv %s part %d/%d offset=%d len=%d%n",
                            name, part, partCount, offset, len);

                    synchronized (lock) {
                        // Initialize/truncate target file once for each transfer.
                        if (createdCounter[0]) {
                            try (RandomAccessFile raf = new RandomAccessFile(out, "rw")) {
                                raf.setLength(totalSize);
                            }
                        }

                        try (RandomAccessFile raf = new RandomAccessFile(out, "rw")) {
                            raf.seek(offset);
                            byte[] buf = new byte[8192];
                            long remaining = len;
                            while (remaining > 0) {
                                int r = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                                if (r < 0) throw new EOFException();
                                raf.write(buf, 0, r);
                                remaining -= r;
                            }
                        }
                    }

                    int left = counter.decrementAndGet();
                    if (left == 0) {
                        transferDoneSignals.computeIfAbsent(transferKey, k -> new CountDownLatch(1)).countDown();
                        blocksToFinish.remove(transferKey, counter);
                        fileLocks.remove(transferKey, lock);
                        System.out.println(name + " received completely");
                    }
                }
            } catch (EOFException e) {
                // Peer closed before sending a full header/body. This can happen during probing.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                try { s.close(); } catch (IOException ignored) {}
            }
        }
    }

    public static void main(String[] args) throws Exception {
        int port = 5000;
        File dir = new File(".");
        if (args.length >= 1) port = Integer.parseInt(args[0]);
        if (args.length >= 2) dir = new File(args[1]);
        FileTransferServer srv = new FileTransferServer(port, dir);
        srv.start();
    }
}
