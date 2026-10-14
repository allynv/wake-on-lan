import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 组装 APK：把 aapt2 link 产出的 base.apk 与 d8 产出的 classes.dex 合并为一个 zip。
 *
 * <p>为什么不用现成的压缩库？因为 targetSdk &gt;= 30 时 Android 强制要求
 * {@code resources.arsc} 必须“不压缩（STORED）且 4 字节对齐”，否则安装会直接失败
 * （错误大意：resources.arsc of installed APKs must be stored uncompressed）。
 * 而 .NET 的 ZipArchive 即使指定 NoCompression 依然会写成 deflate，
 * 所以这里用 java.util.zip 精确控制每个条目的压缩方式。
 */
public final class ZipBuilder {

    /** 固定时间戳（1980-01-01），保证每次构建产物一致。 */
    private static final long FIXED_TIME = 315532800000L;

    private ZipBuilder() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("用法: ZipBuilder <base.apk> <classes.dex> <out.apk>");
            System.exit(2);
        }
        File baseApk = new File(args[0]);
        File dexFile = new File(args[1]);
        File outApk = new File(args[2]);
        if (!baseApk.isFile()) {
            throw new IOException("找不到 base apk：" + baseApk);
        }
        if (!dexFile.isFile()) {
            throw new IOException("找不到 classes.dex：" + dexFile);
        }

        int stored = 0;
        int deflated = 0;
        ZipFile source = new ZipFile(baseApk);
        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(outApk));
        try {
            out.setLevel(9);

            List<String> names = new ArrayList<String>();
            Enumeration<? extends ZipEntry> entries = source.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                names.add(entry.getName());
            }

            for (String name : names) {
                ZipEntry entry = source.getEntry(name);
                byte[] data = readAll(source.getInputStream(entry));
                boolean store = mustStore(name);
                writeEntry(out, name, data, store);
                if (store) {
                    stored++;
                } else {
                    deflated++;
                }
            }

            // classes.dex 不压缩，可以 mmap 加载，启动更快
            writeEntry(out, "classes.dex", readAll(new FileInputStream(dexFile)), true);
            stored++;
        } finally {
            out.close();
            source.close();
        }

        System.out.println("已生成 " + outApk.getName()
                + "（STORED " + stored + " 项 / DEFLATED " + deflated + " 项，"
                + outApk.length() + " 字节）");
    }

    private static boolean mustStore(String name) {
        return "resources.arsc".equals(name) || name.endsWith(".dex");
    }

    private static void writeEntry(ZipOutputStream out, String name, byte[] data, boolean store)
            throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(FIXED_TIME);
        if (store) {
            CRC32 crc = new CRC32();
            crc.update(data);
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(data.length);
            entry.setCompressedSize(data.length);
            entry.setCrc(crc.getValue());
        } else {
            entry.setMethod(ZipEntry.DEFLATED);
        }
        out.putNextEntry(entry);
        out.write(data);
        out.closeEntry();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(16384);
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } finally {
            in.close();
        }
    }
}
