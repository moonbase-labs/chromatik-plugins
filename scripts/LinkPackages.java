import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class LinkPackages {

  private static final List<String> PLATFORMS =
    List.of("macos", "windows", "linux-x86_64", "linux-arm64");

  private static final String STAMP =
    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd-HH.mm.ss"));

  private static int linked = 0;
  private static int problems = 0;
  private static boolean copiedInstead = false;

  public static void main(String[] args) throws IOException {
    final Path modules = Path.of("packages");

    if (!Files.isDirectory(modules)) {
      System.err.println("Run this from the root of chromatik-plugins.");
      System.exit(1);
    }

    final Path chromatik = args.length > 0
      ? Path.of(args[0]).toAbsolutePath().normalize()
      : Path.of(System.getProperty("user.home"), "Chromatik");

    if (!Files.isDirectory(chromatik)) {
      System.err.println("No Chromatik folder at " + chromatik
        + ". Start Chromatik once to create it, or pass its path.");
      System.exit(1);
    }

    final Path packages = Files.createDirectories(chromatik.resolve("Packages"));
    final String platform = currentPlatform();
    final String buildCommand = platform.equals("macos") ? "mvn package" : "mvn package -Pdist-" + platform;
    final List<String> unbuilt = new ArrayList<>();

    for (Path module : list(modules)) {
      if (!Files.isDirectory(module)) {
        continue;
      }

      final String moduleName = module.getFileName().toString();
      final List<Path> jars = builtJars(module.resolve("target"), moduleName);
      final Optional<Path> jar = pickJar(jars, platform);

      if (jar.isPresent()) {
        link(packages, moduleName, jar.get().toAbsolutePath().normalize());
      } else if (jars.isEmpty()) {
        unbuilt.add(moduleName);
      } else {
        System.out.printf("skipped   %s: only built for another platform. Run %s%n", moduleName, buildCommand);
        problems++;
      }
    }

    if (!unbuilt.isEmpty()) {
      System.out.printf("not built %s. Run %s first.%n", String.join(", ", unbuilt), buildCommand);
    }

    if (copiedInstead) {
      System.out.println();
      System.out.println("Windows wouldn't create symbolic links, so the jars were copied instead.");
      System.out.println("Turn on Developer Mode (Settings > System > For developers) and run this again to link them,");
      System.out.println("or run it again after every build.");
    }

    System.exit(problems == 0 && linked > 0 ? 0 : 1);
  }

  private static void link(Path packages, String moduleName, Path jar) {
    final Path link = packages.resolve(moduleName + ".jar");

    try {
      for (Path existing : list(packages)) {
        final String fileName = existing.getFileName().toString();

        if (existing.equals(link) || !fileName.startsWith(moduleName + "-") || !fileName.endsWith(".jar")) {
          continue;
        }

        if (Files.isSymbolicLink(existing)) {
          Files.delete(existing);
          System.out.printf("removed   %s, an old link%n", fileName);
        } else {
          final Path backup = existing.resolveSibling(fileName + ".bak." + STAMP);
          Files.move(existing, backup);
          System.out.printf("moved     %s to %s%n", fileName, backup.getFileName());
        }
      }

      if (Files.isSymbolicLink(link) && Files.readSymbolicLink(link).equals(jar)) {
        System.out.printf("ok        %s -> %s%n", link.getFileName(), jar);
        linked++;
        return;
      }

      Files.deleteIfExists(link);

      try {
        Files.createSymbolicLink(link, jar);
        System.out.printf("linked    %s -> %s%n", link.getFileName(), jar);
      } catch (FileSystemException refused) {
        if (!currentPlatform().equals("windows")) {
          throw refused;
        }

        Files.copy(jar, link, StandardCopyOption.REPLACE_EXISTING);
        copiedInstead = true;
        System.out.printf("copied    %s from %s%n", link.getFileName(), jar);
      }

      linked++;
    } catch (IOException failure) {
      System.err.printf("failed    %s: %s%n", moduleName, failure);
      problems++;
    }
  }

  private static List<Path> builtJars(Path target, String moduleName) throws IOException {
    if (!Files.isDirectory(target)) {
      return List.of();
    }

    return list(target).stream()
      .filter(jar -> {
        final String fileName = jar.getFileName().toString();

        return fileName.startsWith(moduleName + "-")
          && fileName.endsWith(".jar")
          && !fileName.endsWith("-sources.jar")
          && !fileName.endsWith("-javadoc.jar");
      })
      .toList();
  }

  private static Optional<Path> pickJar(List<Path> jars, String platform) {
    final Comparator<Path> newest = Comparator.comparing(LinkPackages::modified);
    final Optional<Path> forThisPlatform = jars.stream()
      .filter(jar -> jar.getFileName().toString().endsWith("-" + platform + ".jar"))
      .max(newest);

    if (forThisPlatform.isPresent()) {
      return forThisPlatform;
    }

    return jars.stream()
      .filter(jar -> PLATFORMS.stream().noneMatch(other -> jar.getFileName().toString().endsWith("-" + other + ".jar")))
      .max(newest);
  }

  private static String currentPlatform() {
    final String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
    final String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);

    if (os.contains("mac")) {
      return "macos";
    }

    if (os.contains("win")) {
      return "windows";
    }

    return arch.equals("aarch64") || arch.equals("arm64") ? "linux-arm64" : "linux-x86_64";
  }

  private static List<Path> list(Path directory) throws IOException {
    final List<Path> entries = new ArrayList<>();

    try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
      stream.forEach(entries::add);
    }

    entries.sort(Comparator.naturalOrder());

    return entries;
  }

  private static FileTime modified(Path file) {
    try {
      return Files.getLastModifiedTime(file);
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }
}
