package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The cucumber atom, started the way the runner starts it.
 *
 * <p>What {@code tools/smoke.sh} checked: the scenario counts (the thing a build gates on) and that a file that is not
 * cucumber json is refused instead of counted as zero.
 */
class CucumberReportIT {

    private static final String CUCUMBER_JSON = """
            [{"uri":"features/login.feature","name":"Login","elements":[
              {"name":"valid login","steps":[{"result":{"status":"passed"}}]},
              {"name":"bad password","steps":[{"result":{"status":"failed"}}]},
              {"name":"skipped one","steps":[{"result":{"status":"skipped"}}]}
            ]}]
            """;

    private Atom atom;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    @Test
    @DisplayName("scenario counts, with skipMarkingUnstable keeping a failing scenario from marking the build")
    void countsScenarios() throws Exception {
        AtomResult run = atom.file("cucumber.json", CUCUMBER_JSON)
                .input("pattern", "**/cucumber.json")
                .input("skipMarkingUnstable", "true")
                .run();

        run.assertOk();
        assertEquals("3", run.field("scenarios"));
        assertEquals("1", run.field("failed"));
        assertEquals("1", run.field("passed"));
        assertEquals("1", run.field("skipped"));
    }

    @Test
    @DisplayName("a json that is not cucumber output fails instead of reporting zero scenarios")
    void refusesNonCucumberJson() throws Exception {
        AtomResult run = atom.file("x.json", "not json")
                .input("pattern", "*.json")
                .run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("40002"),
                "not cucumber json is a USER error 40002:\n" + run.document());
    }
}
