import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.Normalizer;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipOutputStream;
import java.util.zip.ZipEntry;

/** Reproducible character 1..4-gram counts from Tatoeba; no phrase rules. */
class TrainSurfaceModel {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Usage: java TrainSurfaceModel.java jpn_sentences_detailed.tsv model.bin attribution.tsv.zip");
        List<Map<Long, Integer>> counts = new ArrayList<>();
        for (int n = 0; n < 4; n++) counts.add(new HashMap<>());
        Set<String> seen = new HashSet<>();
        List<String> credits = new ArrayList<>();
        int training = 0, heldOut = 0;
        long total = 0;
        try (BufferedReader input = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8)) {
            for (String line; (line = input.readLine()) != null;) {
                String[] fields = line.split("\t", -1);
                if (fields.length < 4 || !fields[1].equals("jpn")) continue;
                String sentence = Normalizer.normalize(fields[2], Normalizer.Form.NFKC).strip();
                if (sentence.length() < 3 || sentence.length() > 256 || !seen.add(sentence)) continue;
                // Sentence-based, deterministic split independent of row ordering and example IDs.
                if (Math.floorMod(sentence.hashCode(), 10) == 0) { heldOut++; continue; }
                training++;
                credits.add(fields[0] + "\t" + fields[3]);
                for (int end = 0; end < sentence.length(); end++) {
                    long key = 0;
                    for (int n = 1; n <= 4 && end - n + 1 >= 0; n++) {
                        key |= (long) sentence.charAt(end - n + 1) << (16 * (n - 1));
                        counts.get(n - 1).merge(key, 1, Integer::sum);
                    }
                    total++;
                }
            }
        }
        if (training < 1000) throw new IllegalArgumentException("Insufficient Japanese sentences: " + training);
        Path output = Path.of(args[1]);
        Files.createDirectories(output.toAbsolutePath().getParent());
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(output)))) {
            out.writeInt(0x53554D31); // SUM1
            out.writeInt(1);
            out.writeLong(total);
            for (int order = 1; order <= 4; order++) {
                int minimum = order == 1 ? 1 : order == 4 ? 3 : 2;
                List<Map.Entry<Long, Integer>> entries = counts.get(order - 1).entrySet().stream()
                    .filter(e -> e.getValue() >= minimum).sorted(Map.Entry.comparingByKey()).toList();
                out.writeInt(entries.size());
                for (Map.Entry<Long, Integer> entry : entries) {
                    out.writeLong(entry.getKey());
                    out.writeInt(entry.getValue());
                }
                System.out.println("order=" + order + " entries=" + entries.size());
            }
        }
        Path attribution = Path.of(args[2]);
        Files.createDirectories(attribution.toAbsolutePath().getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(attribution))) {
            ZipEntry entry = new ZipEntry("TATOEBA-ATTRIBUTION.tsv");
            entry.setTime(0L);
            zip.putNextEntry(entry);
            Writer out = new OutputStreamWriter(zip, StandardCharsets.UTF_8);
            out.write("# Tatoeba sentence id\tcontributor (\\N means no current owner)\n");
            for (String credit : credits) out.write(credit + "\n");
            out.flush();
            zip.closeEntry();
        }
        System.out.println("training=" + training + " heldOut=" + heldOut + " characters=" + total + " bytes=" + Files.size(output));
    }
}
