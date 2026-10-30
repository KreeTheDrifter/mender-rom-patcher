package io.github.kreethedrifter.mender.patch;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Patch engine checks. Fixtures in src/test/resources/fixtures were made with
 * RomPatcher.js (an independent implementation): NAME.orig + NAME.FORMAT -> NAME.
 * Runs under JUnit (PatcherTest) or on its own with main().
 */
public final class PatcherChecks {

    static final String[] CASES = {"small", "grow", "shrink", "rle"};
    static final String[] FORMATS = {"ips", "bps", "ups"};

    /** Returns the list of failures (empty = all passed). */
    public static List<String> run() throws IOException {
        List<String> fails = new ArrayList<>();
        for (String c : CASES) {
            byte[] orig = load(c + ".orig"), want = load(c);
            for (String f : FORMATS) {
                String name = c + "." + f;
                try {
                    Patcher.Result r = Patcher.apply(orig, load(name));
                    if (!Arrays.equals(r.output, want)) fails.add(name + ": wrong output");
                    if (!r.format.name().equalsIgnoreCase(f)) fails.add(name + ": detected as " + r.format);
                } catch (PatchException e) {
                    fails.add(name + ": " + e.getMessage());
                }
            }
        }

        // a ROM with a 512-byte copier header is accepted (header removed)
        try {
            Patcher.Result r = Patcher.apply(load("headered.orig"), load("grow.bps"));
            if (!Arrays.equals(r.output, load("grow")) || r.note == null) fails.add("headered BPS: not handled");
        } catch (PatchException e) {
            fails.add("headered BPS: " + e.getMessage());
        }

        // UPS applied to an already-patched ROM restores the original
        try {
            Patcher.Result r = Patcher.apply(load("small"), load("small.ups"));
            if (!Arrays.equals(r.output, load("small.orig"))) fails.add("UPS reverse: wrong output");
        } catch (PatchException e) {
            fails.add("UPS reverse: " + e.getMessage());
        }

        // wrong ROM is refused for checksummed formats
        for (String f : new String[] {"bps", "ups"}) {
            try {
                Patcher.apply(load("rle.orig"), load("small." + f));
                fails.add("wrong ROM accepted by " + f);
            } catch (PatchException expected) {
                if (!expected.getMessage().contains("different ROM")) fails.add("wrong ROM message for " + f);
            }
        }

        // damaged patch is refused, not crashed on
        byte[] bad = load("grow.bps");
        bad[bad.length / 2] ^= 0x55;
        try {
            Patcher.apply(load("grow.orig"), bad);
            fails.add("damaged BPS accepted");
        } catch (PatchException expected) { /* ok */ }
        byte[] cut = Arrays.copyOf(load("grow.ips"), 400);
        try {
            Patcher.apply(load("grow.orig"), cut);
            fails.add("truncated IPS accepted");
        } catch (PatchException expected) { /* ok */ }

        // inspect: BPS/UPS know their ROM, IPS doesn't
        try {
            byte[] orig = load("grow.orig");
            long crc = Patcher.crc32(orig);
            for (String f : new String[] {"bps", "ups"}) {
                Patcher.Info info = Patcher.inspect(load("grow." + f));
                if (info.sourceCrc != crc || info.sourceSize != orig.length || !info.accepts(crc, orig.length)) {
                    fails.add("inspect " + f + ": wrong source info");
                }
                if (info.accepts(Patcher.crc32(load("rle.orig")), 4096)) fails.add("inspect " + f + ": accepts wrong ROM");
            }
            Patcher.Info ips = Patcher.inspect(load("grow.ips"));
            if (ips.format != Patcher.Format.IPS || ips.sourceCrc != -1) fails.add("inspect ips");
            byte[] patched = load("grow");
            if (!Patcher.inspect(load("grow.ups")).accepts(Patcher.crc32(patched), patched.length)) {
                fails.add("inspect ups: patched ROM should be accepted (reverse)");
            }
        } catch (PatchException e) {
            fails.add("inspect: " + e.getMessage());
        }

        // not a patch
        try {
            Patcher.apply(load("small.orig"), load("small.orig"));
            fails.add("random file accepted as a patch");
        } catch (PatchException expected) { /* ok */ }
        return fails;
    }

    static byte[] load(String name) throws IOException {
        try (InputStream in = PatcherChecks.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) throw new IOException("missing fixture " + name);
            return in.readAllBytes();
        }
    }

    public static void main(String[] args) throws IOException {
        List<String> fails = run();
        for (String f : fails) System.out.println("FAIL " + f);
        System.out.println(fails.isEmpty() ? "ALL PASS" : fails.size() + " FAILED");
        // optional: big real-world check, args = ROM PATCH EXPECTED
        if (args.length == 3) {
            byte[] rom = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(args[0]));
            byte[] patch = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(args[1]));
            byte[] want = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(args[2]));
            long t = System.currentTimeMillis();
            try {
                Patcher.Result r = Patcher.apply(rom, patch);
                System.out.println((Arrays.equals(r.output, want) ? "PASS " : "FAIL ") + args[1]
                        + " (" + (System.currentTimeMillis() - t) + " ms)");
            } catch (PatchException e) {
                System.out.println("FAIL " + args[1] + ": " + e.getMessage());
            }
        }
        if (!fails.isEmpty()) System.exit(1);
    }
}
