package dev.dupelens;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;

/** Read-only duplicate discovery. Symbolic links are never followed. */
public final class Main {
    public record Group(long size, List<Path> files) {
        public long reclaimable() { return size * (files.size() - 1); }
    }
    public record Scan(List<Group> groups, List<String> warnings, long files) {}

    public static void main(String[] args) {
        if (args.length == 0 || args[0].equals("--help")) {
            System.out.println("DupeLens — find duplicate files, change nothing.\nUsage: java -jar dupelens.jar <directory> [--html <report.html>]");
            return;
        }
        try {
            if (args.length != 1 && !(args.length == 3 && args[1].equals("--html")))
                throw new IllegalArgumentException("Expected <directory> [--html <report.html>]");
            Scan scan = scan(Path.of(args[0]));
            long bytes = scan.groups.stream().mapToLong(Group::reclaimable).sum();
            System.out.printf("Scanned %d files | %d duplicate groups | %,d redundant bytes%n", scan.files, scan.groups.size(), bytes);
            for (Group group : scan.groups) {
                System.out.printf("%n%,d bytes each (%d copies)%n", group.size, group.files.size());
                group.files.forEach(p -> System.out.println("  " + p));
            }
            scan.warnings.forEach(w -> System.err.println("Warning: " + w));
            if (args.length == 3) {
                Path report = Path.of(args[2]).toAbsolutePath();
                // Never replace an existing file, even if it happens to be an input.
                Files.writeString(report, html(scan), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                System.out.println("\nReport: " + report);
            }
        } catch (Exception e) {
            System.err.println("DupeLens: " + e.getMessage());
            System.exit(1);
        }
    }

    public static Scan scan(Path root) throws IOException {
        root = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Not a directory: " + root);
        Map<Long, List<Path>> sizes = new TreeMap<>();
        List<String> warnings = new ArrayList<>();
        Set<Object> identities = new HashSet<>();
        long[] count = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) {
                    count[0]++;
                    // Hard links already share storage; don't count them twice where file keys exist.
                    if (attrs.fileKey() != null && !identities.add(attrs.fileKey())) return FileVisitResult.CONTINUE;
                    sizes.computeIfAbsent(attrs.size(), ignored -> new ArrayList<>()).add(path);
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path path, IOException error) {
                warnings.add(path + ": " + error.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });
        List<Group> groups = new ArrayList<>();
        for (var bucket : sizes.entrySet()) {
            if (bucket.getValue().size() < 2) continue;
            Map<String, List<Path>> hashes = new TreeMap<>();
            for (Path path : bucket.getValue()) {
                try {
                    BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!before.isRegularFile() || before.size() != bucket.getKey()) throw new IOException("file changed during scan");
                    String hash = digest(path);
                    BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                            || !Objects.equals(before.fileKey(), after.fileKey())) throw new IOException("file changed during scan");
                    // Windows providers may return null file keys. Check actual file identity
                    // within matching-content buckets before counting another physical copy.
                    List<Path> matches = hashes.computeIfAbsent(hash, ignored -> new ArrayList<>());
                    boolean alias = false;
                    for (Path existing : matches) {
                        if (Files.isSameFile(existing, path)) { alias = true; break; }
                    }
                    if (!alias) matches.add(path);
                } catch (IOException e) { warnings.add(path + ": " + e.getMessage()); }
            }
            for (List<Path> paths : hashes.values()) if (paths.size() > 1) {
                paths.sort(Comparator.comparing(Path::toString));
                groups.add(new Group(bucket.getKey(), List.copyOf(paths)));
            }
        }
        groups.sort(Comparator.comparingLong(Group::reclaimable).reversed().thenComparing(g -> g.files.get(0).toString()));
        return new Scan(List.copyOf(groups), List.copyOf(warnings), count[0]);
    }

    private static String digest(Path path) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = in.read(buffer)) != -1) md.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    static String readable(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KiB", "MiB", "GiB", "TiB", "PiB", "EiB"};
        double value = bytes;
        int unit = -1;
        do { value /= 1024; unit++; } while (value >= 1024 && unit < units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    private static String icon(String type) {
        String shape = switch (type) {
            case "folder" -> "<path d='M3 7h6l2 3h10v11H3z'/>";
            case "layers" -> "<path d='m12 3 10 5-10 5L2 8zM2 13l10 5 10-5M2 18l10 5 10-5'/>";
            case "image" -> "<rect x='3' y='3' width='18' height='18' rx='2'/><circle cx='8' cy='8' r='1'/><path d='m3 18 6-6 4 4 4-6 4 5'/>";
            default -> "<path d='M5 2h9l5 5v15H5zM14 2v6h5'/>";
        };
        return "<svg aria-hidden='true' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='1.7' stroke-linejoin='round' stroke-linecap='round'>" + shape + "</svg>";
    }

    public static String html(Scan scan) {
        long bytes = scan.groups.stream().mapToLong(Group::reclaimable).sum();
        StringBuilder out = new StringBuilder("""
            <!doctype html>
            <html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>DupeLens — Duplicate Report</title>
            <style>
            *{box-sizing:border-box}html{scroll-behavior:smooth}body{margin:0;background:#eef0f7;color:#101216;font:16px "Segoe UI",Arial,sans-serif}
            .shell{max-width:1500px;margin:auto;min-height:100vh;background:linear-gradient(130deg,#f8f9fd,#eceff7);border:1px solid #d1d7e5;border-radius:30px;overflow:hidden}
            .chrome{height:76px;background:linear-gradient(120deg,#e9edf6,#e2e7f2);border-bottom:1px solid #cdd5e4;display:flex;align-items:center;gap:18px;padding:0 36px}
            .dot{width:28px;height:28px;border:1px solid #fff9;border-radius:50%;box-shadow:0 0 0 1px #0001}.red{background:#ff6258}.yellow{background:#ffbd2e}.green{background:#28c840}
            header{display:flex;align-items:center;gap:35px;padding:22px 45px;border-bottom:1px solid #dce1ec}
            .brand{display:flex;align-items:center;gap:22px;flex:1}.brand svg{width:48px;height:48px}.brand strong{font-size:32px;letter-spacing:-1px}.subtitle{color:#46526a;font-size:21px}
            nav{display:flex;gap:16px;align-items:center}nav a{color:#344059;text-decoration:none;padding:13px 28px;border-radius:28px;font-size:20px}nav a.active{background:#ffffffab;border:1px solid #d4dae7;box-shadow:0 4px 8px #25314914;color:#111;font-weight:650}
            .report-label{margin-left:auto;color:#39455d;white-space:nowrap}.info{font-size:20px;border:2px solid #657086;border-radius:50%;text-align:center;width:30px;height:30px;text-decoration:none;color:#46526a}
            main{padding:20px 32px}.hero{position:relative;display:flex;align-items:center;justify-content:space-between;gap:20px;background:radial-gradient(ellipse at 70% 130%,#202a38,#101820 75%);color:#f8f9fc;border:1px solid #080e16;border-radius:24px;padding:32px 60px;min-height:288px}
            .badges{display:flex;gap:18px}.badge{background:linear-gradient(#303a46,#252e39);border:1px solid #657081;border-radius:26px;padding:10px 24px;font-size:17px;font-weight:600;box-shadow:inset 0 0 0 2px #ffffff09}
            h1{font-size:clamp(30px,3.7vw,58px);letter-spacing:-1.7px;line-height:1.12;margin:20px 0 14px}.hero p{font-size:25px;color:#e1e5ec;line-height:1.5;margin:0;max-width:810px}.art{position:relative;flex:0 0 185px;height:190px}.paper{position:absolute;top:0;left:0;width:115px;height:140px;border:3px solid #adb8c8;border-radius:26px 8px 24px 24px;background:linear-gradient(140deg,#566174,#252f3e);box-shadow:inset 0 0 0 3px #ffffff20,0 8px 10px #0005}.paper:after{content:"";position:absolute;right:-3px;top:-3px;width:40px;height:40px;background:linear-gradient(45deg,#7b8596,#d1d7e1);clip-path:polygon(0 0,100% 100%,0 100%);border-radius:0 0 0 10px}.paper.back{top:56px;left:60px}
            .stats{display:flex;align-items:center;margin:22px 0 20px;padding:24px 20px;background:#ffffff8c;border:1px solid #d5dce9;border-radius:24px;box-shadow:0 2px 3px #29364e08}
            .stat{display:flex;align-items:center;justify-content:center;gap:30px;flex:1;font-size:25px}.stat+.stat{border-left:2px solid #b8c1d4}.stat svg{width:40px;height:40px;color:#5a677e}.stat strong{font-weight:750}
            .group{display:flex;align-items:flex-start;gap:36px;background:#ffffff9c;border:1px solid #d6ddea;border-radius:24px;padding:24px 28px 18px 36px;margin:18px 0;box-shadow:0 2px 3px #2430440d}
            .file-icon{background:linear-gradient(140deg,#eef1f7,#e3e7f0);border:1px solid #e3e8f1;border-radius:20px;padding:22px;color:#566176}.file-icon svg{width:42px;height:42px;display:block}
            details{flex:1;min-width:0}summary{cursor:pointer;list-style:none;display:flex;align-items:center;gap:20px}summary::-webkit-details-marker{display:none}summary:after{content:"";width:15px;height:15px;border-right:3px solid #47536c;border-bottom:3px solid #47536c;transform:rotate(45deg);margin:0 14px 8px 20px}details:not([open]) summary:after{transform:rotate(-45deg);margin-bottom:0}
            h2{font-size:29px;letter-spacing:-.7px;margin:0 0 2px;overflow-wrap:anywhere}.meta{font-size:21px;color:#46536c}.count{margin-left:auto;background:#e7ebf3;border:1px solid #e1e6f0;white-space:nowrap;border-radius:26px;padding:8px 22px;font-size:20px;font-weight:650}
            .paths{margin:9px 0 0;padding:7px 26px;background:#e9edf5;border:1px solid #e5e9f2;border-radius:16px;list-style:none;font:19px/1.45 Consolas,monospace;overflow-wrap:anywhere}
            .notice{padding:24px 30px;border:1px solid #d6ddea;border-radius:20px;background:#ffffff9c}.notice li{overflow-wrap:anywhere}footer{padding:16px 8px 24px;color:#58657c;font-size:14px}a:focus-visible,summary:focus-visible{outline:3px solid #5179c6;outline-offset:5px}
            @media(max-width:1050px){header{padding:20px 28px;gap:20px;flex-wrap:wrap}.brand strong{font-size:27px}nav a{padding:10px 18px}.hero{padding:30px}.hero p{font-size:21px}.art{flex-basis:145px;transform:scale(.8);transform-origin:right center}.stat{font-size:20px;gap:14px}}
            @media(max-width:700px){.shell{border-radius:0}.chrome{height:45px;padding:0 20px;gap:10px}.dot{width:16px;height:16px}header{padding:18px;gap:14px}.brand{gap:12px}.brand strong{font-size:24px}.subtitle{font-size:16px}.report-label{display:none}nav{order:3;width:100%;gap:8px}nav a{font-size:16px;flex:1;text-align:center}.info{margin-left:auto}main{padding:16px}.hero{padding:24px;min-height:0}.hero p{font-size:18px}.art{display:none}.badge{font-size:12px;padding:8px 12px}.badges{gap:8px}.stats{padding:8px 18px;display:block}.stat{justify-content:flex-start;padding:15px 0;font-size:19px}.stat+.stat{border-left:0;border-top:1px solid #d5dce9}.stat svg{width:28px;height:28px}.group{padding:18px;gap:12px;border-radius:18px}.file-icon{padding:10px;border-radius:12px}.file-icon svg{width:24px;height:24px}h2{font-size:21px}.meta{font-size:15px}.count{font-size:13px;padding:6px 10px}summary{gap:8px;flex-wrap:wrap}summary:after{width:10px;height:10px;margin-left:4px}.paths{font-size:13px;padding:10px}.notice{padding:18px}}
            @media(prefers-reduced-motion:reduce){html{scroll-behavior:auto}}
            </style></head><body><div class="shell">
            <div class="chrome" aria-hidden="true"><i class="dot red"></i><i class="dot yellow"></i><i class="dot green"></i></div>
            <header><div class="brand">
            """);
        out.append(icon("file")).append("""
            <div><strong>DupeLens</strong><div class="subtitle">Duplicate Report</div></div></div>
            <nav aria-label="Report navigation"><a class="active" href="#report">Report</a><a href="#groups">Groups</a><a href="#files">Files</a></nav>
            <span class="report-label">Offline report</span><a class="info" href="#about" aria-label="About this report">i</a></header>
            <main id="report"><section class="hero" aria-labelledby="headline"><div><div class="badges"><span class="badge">↓ &nbsp; OFFLINE HTML</span><span class="badge">▣ &nbsp; READ-ONLY</span></div>
            <h1 id="headline">Your duplicates, beautifully in view.</h1><p>A clean, shareable report of duplicate files, grouped by content,<br> with size impact and exact file paths.</p></div><div class="art" aria-hidden="true"><div class="paper"></div><div class="paper back"></div></div></section>
            <section class="stats" id="files" aria-label="Scan summary">
            """);
        out.append("<div class='stat'>").append(icon("folder")).append("<span>Scanned <strong>")
            .append(String.format(Locale.ROOT, "%,d", scan.files)).append("</strong> files</span></div><div class='stat'>")
            .append(icon("layers")).append("<span><strong>").append(scan.groups.size())
            .append("</strong> duplicate groups</span></div><div class='stat'>").append(icon("file"))
            .append("<span><strong>").append(readable(bytes)).append("</strong> redundant</span></div></section><section id='groups' aria-label='Duplicate groups'>");
        for (Group group : scan.groups) {
            String name = group.files.get(0).getFileName().toString();
            boolean image = name.toLowerCase(Locale.ROOT).matches(".*\\.(png|jpe?g|gif|bmp|webp|svg)");
            out.append("<article class='group'><div class='file-icon'>").append(icon(image ? "image" : "file"))
                .append("</div><details open><summary><div><h2>").append(escape(name))
                .append("</h2><div class='meta'>").append(group.files.size()).append(" copies · ")
                .append(readable(group.reclaimable())).append(" redundant</div></div><span class='count'>")
                .append(group.files.size()).append(" files</span></summary><ul class='paths'>");
            group.files.forEach(p -> out.append("<li>").append(escape(p.toString())).append("</li>"));
            out.append("</ul></details></article>");
        }
        if (scan.groups.isEmpty()) out.append("<div class='notice'>No duplicates found.</div>");
        out.append("</section>");
        if (!scan.warnings.isEmpty()) {
            out.append("<section class='notice'><h2>Warnings</h2><ul>");
            scan.warnings.forEach(w -> out.append("<li>").append(escape(w)).append("</li>"));
            out.append("</ul></section>");
        }
        return out.append("<footer id='about'>Read-only report. No files modified. Redundant space is an estimate. Review full paths before sharing this report.</footer></main></div></body></html>").toString();
    }
}
