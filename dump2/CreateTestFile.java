import java.io.FileOutputStream;

public class CreateTestFile {
    public static void main(String[] args) throws Exception {
        byte[] data = new byte[5000000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte)(i % 256);
        }
        try (FileOutputStream fos = new FileOutputStream("testfile.bin")) {
            fos.write(data);
        }
        System.out.println("Created testfile.bin: " + data.length + " bytes");
    }
}
