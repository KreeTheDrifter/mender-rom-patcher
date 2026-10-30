package io.github.kreethedrifter.mender.patch;

import static org.junit.Assert.assertEquals;

import java.util.Collections;
import org.junit.Test;

public class PatcherTest {
    @Test
    public void allChecksPass() throws Exception {
        assertEquals(Collections.emptyList(), PatcherChecks.run());
    }
}
