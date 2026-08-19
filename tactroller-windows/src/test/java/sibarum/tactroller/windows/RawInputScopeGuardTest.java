package sibarum.tactroller.windows;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The extinction mechanism for the "OS scope wider than abstraction scope" bug class.
 *
 * <h2>The rule this enforces</h2>
 *
 * <p>{@code InputBackend} is a <b>per-window</b> abstraction. Several Win32 input channels are not per-window:
 * RawInput registration is <b>per-process</b> (the most recent registration wins for the whole process) and the
 * cursor clip/visibility/position are <b>global</b>. Binding those from per-window code is how one window ends
 * up silently stealing another's input — the exact shape of the wheel-stops-working bug.
 *
 * <p>So: <b>process-scoped symbols may be bound only from {@link WindowsRawInputHub}</b>, whose whole purpose
 * is to own them once and fan the results out. This test scans compiled bytecode for those symbol names (they
 * appear as string constants in the constant pool, since every binding goes through
 * {@code SymbolLookup.findOrThrow(name)}) and fails if any other class in this package references one.
 *
 * <p>A future channel added to the wrong class therefore breaks the build with the rule's name attached,
 * instead of becoming a runtime mystery in a multi-window app months later. When adding a genuinely new
 * process- or globally-scoped Win32 call, add its symbol here <em>and</em> put the call in the hub.
 */
class RawInputScopeGuardTest {

    /**
     * Win32 symbols whose effect is process-wide or global, mapped to why. Binding any of these outside the
     * hub reintroduces the scope-mismatch bug class.
     */
    private static final Map<String, String> PROCESS_SCOPED_SYMBOLS = Map.of(
            "RegisterRawInputDevices",
            "RawInput registration is per-process: the most recent registration wins, silencing every other window",
            "GetRawInputData",
            "reading a RawInput report belongs to the single process-wide pump",
            "RegisterClassExW",
            "the message-only window class backs the process-wide pump",
            "GetMessageW",
            "only the process-wide pump runs a message loop",
            "DispatchMessageW",
            "only the process-wide pump dispatches messages",
            "ToUnicodeEx",
            "keyboard-to-text translation is driven by the process-wide pump's key-down reports");

    /** Classes allowed to bind the symbols above. */
    private static final String HUB_CLASS = "WindowsRawInputHub";

    @Test
    void processScopedWin32SymbolsAreBoundOnlyByTheHub() throws IOException {
        List<Path> classFiles = compiledClasses();
        assertTrue(classFiles.size() > 1,
                "expected the package's compiled classes on disk; found " + classFiles.size()
                        + " — has the module been compiled?");

        List<String> violations = new ArrayList<>();
        boolean hubSeen = false;
        for (Path classFile : classFiles) {
            String className = classFile.getFileName().toString().replace(".class", "");
            boolean isHub = className.equals(HUB_CLASS) || className.startsWith(HUB_CLASS + "$");
            if (isHub) {
                hubSeen = true;
                continue;
            }
            // Bytecode holds UTF-8 constant-pool entries, so a plain byte-level search finds the symbol names.
            String bytes = new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
            for (Map.Entry<String, String> symbol : PROCESS_SCOPED_SYMBOLS.entrySet()) {
                if (bytes.contains(symbol.getKey())) {
                    violations.add(className + " references " + symbol.getKey()
                            + " — " + symbol.getValue() + ". Move the call into " + HUB_CLASS
                            + " and fan the result out to each backend instance.");
                }
            }
        }

        assertTrue(hubSeen, HUB_CLASS + " was not found among the compiled classes — did it get renamed? "
                + "This guard is only meaningful while the hub exists.");
        if (!violations.isEmpty()) {
            fail("Process-scoped Win32 symbols bound outside " + HUB_CLASS + ":\n  "
                    + String.join("\n  ", violations));
        }
    }

    /**
     * Sanity check on the scanner itself: the hub really does bind these symbols, so a guard that silently
     * stopped matching (a renamed hub, a moved binding, a broken path) cannot pass by finding nothing.
     */
    @Test
    void theHubItselfBindsTheProcessScopedSymbols() throws IOException {
        Path hub = compiledClasses().stream()
                .filter(p -> p.getFileName().toString().equals(HUB_CLASS + ".class"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("compiled " + HUB_CLASS + " not found"));
        String bytes = new String(Files.readAllBytes(hub), StandardCharsets.ISO_8859_1);
        for (String symbol : PROCESS_SCOPED_SYMBOLS.keySet()) {
            assertTrue(bytes.contains(symbol),
                    HUB_CLASS + " no longer binds " + symbol + " — either the hub moved it out (which this "
                            + "guard exists to prevent) or the guard's symbol list is stale.");
        }
    }

    private static List<Path> compiledClasses() throws IOException {
        Path dir = Path.of("target", "classes", "sibarum", "tactroller", "windows");
        if (!Files.isDirectory(dir)) {
            throw new IOException("compiled classes not found at " + dir.toAbsolutePath());
        }
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".class")).toList();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
}
