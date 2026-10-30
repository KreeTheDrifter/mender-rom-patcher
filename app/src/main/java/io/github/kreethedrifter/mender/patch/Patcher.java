package io.github.kreethedrifter.mender.patch;

import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * Applies IPS, BPS and UPS patches. Pure Java (no Android classes) so it can
 * be unit-tested on any machine.
 */
public final class Patcher {

    public enum Format { IPS, BPS, UPS }

    public static final class Result {
        public final byte[] output;
        public final Format format;
        /** Extra information for the user (e.g. "removed a 512-byte header"), or null. */
        public final String note;

        Result(byte[] output, Format format, String note) {
            this.output = output;
            this.format = format;
            this.note = note;
        }
    }

    /** What a patch says about the ROM it needs (BPS and UPS record this; IPS doesn't). */
    public static final class Info {
        public final Format format;
        /** CRC32 of the ROM the patch is made for, or -1 if the format doesn't say. */
        public final long sourceCrc;
        /** Size of the ROM the patch is made for, or -1. */
        public final long sourceSize;
        /** For UPS: CRC32 of the patched ROM (a ROM matching this can be un-patched), or -1. */
        public final long targetCrc;

        Info(Format format, long sourceCrc, long sourceSize, long targetCrc) {
            this.format = format;
            this.sourceCrc = sourceCrc;
            this.sourceSize = sourceSize;
            this.targetCrc = targetCrc;
        }

        /** Whether a ROM with this CRC32 and size fits the patch (always true for IPS). */
        public boolean accepts(long romCrc, long romSize) {
            if (sourceCrc < 0) return true;
            if (romCrc == sourceCrc && romSize == sourceSize) return true;
            return format == Format.UPS && romCrc == targetCrc;
        }
    }

    private Patcher() {}

    /** Reads a patch's format and the ROM it expects, without applying it. */
    public static Info inspect(byte[] patch) throws PatchException {
        Format format = detect(patch);
        if (format == Format.IPS) return new Info(format, -1, -1, -1);
        if (patch.length < 4 + 2 + 12) throw new PatchException("The patch file is damaged or incomplete.");
        try {
            int end = patch.length - 12;
            int[] p = {4};
            long sourceSize = varint(patch, p);
            return new Info(format, u32le(patch, end), sourceSize, format == Format.UPS ? u32le(patch, end + 4) : -1);
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new PatchException("The patch file is damaged or incomplete.");
        }
    }

    public static Format detect(byte[] patch) throws PatchException {
        if (startsWith(patch, "PATCH")) return Format.IPS;
        if (startsWith(patch, "BPS1")) return Format.BPS;
        if (startsWith(patch, "UPS1")) return Format.UPS;
        throw new PatchException("This isn't a patch file Mender understands. Use an .ips, .bps or .ups patch.");
    }

    public static Result apply(byte[] rom, byte[] patch) throws PatchException {
        Format format = detect(patch);
        try {
            switch (format) {
                case IPS: return new Result(applyIps(rom, patch), Format.IPS, null);
                case BPS: return applyBps(rom, patch);
                default:  return applyUps(rom, patch);
            }
        } catch (ArrayIndexOutOfBoundsException | NegativeArraySizeException e) {
            throw new PatchException("The patch file is damaged or incomplete.");
        }
    }

    public static long crc32(byte[] data, int offset, int length) {
        CRC32 crc = new CRC32();
        crc.update(data, offset, length);
        return crc.getValue();
    }

    public static long crc32(byte[] data) {
        return crc32(data, 0, data.length);
    }

    // ---------------------------------------------------------------- IPS

    static byte[] applyIps(byte[] rom, byte[] patch) throws PatchException {
        // First pass: find the output size.
        int pos = 5;
        long size = rom.length;
        Integer truncate = null;
        while (true) {
            if (pos + 3 > patch.length) throw new PatchException("The patch file is damaged or incomplete.");
            int offset = u24(patch, pos);
            if (offset == 0x454F46 && isIpsEnd(patch, pos)) {
                if (pos + 6 == patch.length) truncate = u24(patch, pos + 3);
                break;
            }
            pos += 3;
            int len = u16(patch, pos);
            pos += 2;
            if (len == 0) {
                int count = u16(patch, pos);
                pos += 3;
                size = Math.max(size, (long) offset + count);
            } else {
                pos += len;
                size = Math.max(size, (long) offset + len);
            }
        }
        if (size > Integer.MAX_VALUE - 8) throw new PatchException("The patched ROM would be too large.");

        byte[] out = Arrays.copyOf(rom, (int) size);
        pos = 5;
        while (true) {
            int offset = u24(patch, pos);
            if (offset == 0x454F46 && isIpsEnd(patch, pos)) break;
            pos += 3;
            int len = u16(patch, pos);
            pos += 2;
            if (len == 0) {
                int count = u16(patch, pos);
                byte value = patch[pos + 2];
                pos += 3;
                Arrays.fill(out, offset, offset + count, value);
            } else {
                System.arraycopy(patch, pos, out, offset, len);
                pos += len;
            }
        }
        if (truncate != null && truncate < out.length) out = Arrays.copyOf(out, truncate);
        return out;
    }

    /** "EOF" ends an IPS patch only when nothing (or a 3-byte truncate size) follows. */
    private static boolean isIpsEnd(byte[] patch, int pos) {
        int rest = patch.length - (pos + 3);
        return rest == 0 || rest == 3;
    }

    // ---------------------------------------------------------------- BPS

    static Result applyBps(byte[] rom, byte[] patch) throws PatchException {
        if (patch.length < 4 + 3 + 12) throw new PatchException("The patch file is damaged or incomplete.");
        int end = patch.length - 12;
        long patchCrc = u32le(patch, end + 8);
        if (crc32(patch, 0, patch.length - 4) != patchCrc) {
            throw new PatchException("The patch file is damaged (its checksum doesn't match). Download it again.");
        }
        long sourceCrc = u32le(patch, end);
        long targetCrc = u32le(patch, end + 4);

        int[] p = {4};
        long sourceSize = varint(patch, p);
        long targetSize = varint(patch, p);
        long metaSize = varint(patch, p);
        p[0] += (int) metaSize;
        if (targetSize > Integer.MAX_VALUE - 8) throw new PatchException("The patched ROM would be too large.");

        String note = null;
        byte[] source = rom;
        if (crc32(rom) != sourceCrc) {
            byte[] fixed = tryHeader(rom, sourceSize, sourceCrc);
            if (fixed == null) throw wrongRom(sourceCrc, rom);
            source = fixed;
            note = fixed.length < rom.length
                    ? "Your ROM had a 512-byte header; Mender removed it so the patch would fit."
                    : null;
        }

        byte[] target = new byte[(int) targetSize];
        int out = 0;
        long sourceRel = 0, targetRel = 0;
        while (p[0] < end) {
            long data = varint(patch, p);
            int command = (int) (data & 3);
            int length = (int) ((data >>> 2) + 1);
            switch (command) {
                case 0: // SourceRead
                    System.arraycopy(source, out, target, out, length);
                    out += length;
                    break;
                case 1: // TargetRead
                    System.arraycopy(patch, p[0], target, out, length);
                    p[0] += length;
                    out += length;
                    break;
                case 2: { // SourceCopy
                    long d = varint(patch, p);
                    sourceRel += ((d & 1) != 0 ? -1 : 1) * (d >>> 1);
                    System.arraycopy(source, (int) sourceRel, target, out, length);
                    sourceRel += length;
                    out += length;
                    break;
                }
                default: { // TargetCopy (may overlap: copy byte by byte)
                    long d = varint(patch, p);
                    targetRel += ((d & 1) != 0 ? -1 : 1) * (d >>> 1);
                    for (int i = 0; i < length; i++) target[out++] = target[(int) targetRel++];
                    break;
                }
            }
        }
        if (crc32(target) != targetCrc) {
            throw new PatchException("Patching finished but the result doesn't match what the patch expects. The patch may be damaged.");
        }
        return new Result(target, Format.BPS, note);
    }

    // ---------------------------------------------------------------- UPS

    static Result applyUps(byte[] rom, byte[] patch) throws PatchException {
        if (patch.length < 4 + 2 + 12) throw new PatchException("The patch file is damaged or incomplete.");
        int end = patch.length - 12;
        if (crc32(patch, 0, patch.length - 4) != u32le(patch, end + 8)) {
            throw new PatchException("The patch file is damaged (its checksum doesn't match). Download it again.");
        }
        long crcA = u32le(patch, end), crcB = u32le(patch, end + 4);
        int[] p = {4};
        long sizeA = varint(patch, p);
        long sizeB = varint(patch, p);

        String note = null;
        byte[] source = rom;
        long romCrc = crc32(rom);
        long outSize, outCrc;
        if (romCrc == crcA && rom.length == sizeA) {
            outSize = sizeB; outCrc = crcB;
        } else if (romCrc == crcB && rom.length == sizeB) {
            // UPS works both ways: this ROM is already patched, so undo it.
            outSize = sizeA; outCrc = crcA;
            note = "This ROM was already patched, so Mender reversed the patch and restored the original.";
        } else {
            byte[] fixed = tryHeader(rom, sizeA, crcA);
            if (fixed == null) throw wrongRom(crcA, rom);
            source = fixed;
            outSize = sizeB; outCrc = crcB;
            note = "Your ROM had a 512-byte header; Mender removed it so the patch would fit.";
        }
        if (outSize > Integer.MAX_VALUE - 8) throw new PatchException("The patched ROM would be too large.");

        byte[] target = Arrays.copyOf(source, (int) outSize);
        int pos = 0;
        while (p[0] < end) {
            pos += (int) varint(patch, p);
            while (true) {
                byte x = patch[p[0]++];
                if (pos < target.length) {
                    byte s = pos < source.length ? source[pos] : 0;
                    target[pos] = (byte) (s ^ x);
                }
                pos++;
                if (x == 0) break;
            }
        }
        if (crc32(target) != outCrc) {
            throw new PatchException("Patching finished but the result doesn't match what the patch expects. The patch may be damaged.");
        }
        return new Result(target, Format.UPS, note);
    }

    // ---------------------------------------------------------------- helpers

    /** If the ROM matches once a 512-byte copier header is removed, return it without the header. */
    private static byte[] tryHeader(byte[] rom, long expectedSize, long expectedCrc) {
        if (rom.length == expectedSize + 512 && crc32(rom, 512, rom.length - 512) == expectedCrc) {
            return Arrays.copyOfRange(rom, 512, rom.length);
        }
        return null;
    }

    private static PatchException wrongRom(long expectedCrc, byte[] rom) {
        return new PatchException(String.format(
                "This patch is for a different ROM or a different version of the game.\n\n"
                        + "The patch needs a ROM with CRC32 %08X; yours is %08X.",
                expectedCrc, crc32(rom)));
    }

    private static long varint(byte[] b, int[] p) throws PatchException {
        long data = 0, shift = 1;
        while (true) {
            int x = b[p[0]++] & 0xff;
            data += (x & 0x7f) * shift;
            if ((x & 0x80) != 0) break;
            shift <<= 7;
            data += shift;
            if (shift > (1L << 49)) throw new PatchException("The patch file is damaged or incomplete.");
        }
        return data;
    }

    private static boolean startsWith(byte[] b, String magic) {
        if (b.length < magic.length()) return false;
        for (int i = 0; i < magic.length(); i++) if (b[i] != (byte) magic.charAt(i)) return false;
        return true;
    }

    private static int u24(byte[] b, int i) {
        return ((b[i] & 0xff) << 16) | ((b[i + 1] & 0xff) << 8) | (b[i + 2] & 0xff);
    }

    private static int u16(byte[] b, int i) {
        return ((b[i] & 0xff) << 8) | (b[i + 1] & 0xff);
    }

    private static long u32le(byte[] b, int i) {
        return (b[i] & 0xffL) | ((b[i + 1] & 0xffL) << 8) | ((b[i + 2] & 0xffL) << 16) | ((b[i + 3] & 0xffL) << 24);
    }
}
