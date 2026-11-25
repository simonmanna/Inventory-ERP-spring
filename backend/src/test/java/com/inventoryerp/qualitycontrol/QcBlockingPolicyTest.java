package com.inventoryerp.qualitycontrol;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.qualitycontrol.application.QcBlockingPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** ADR-S087 §5: blocking and blockOnFailure are independent; only generated checks block. */
class QcBlockingPolicyTest {

    @ParameterizedTest(name = "blocking={0} blockOnFailure={1}: pending blocks={2}, failed blocks={3}")
    @CsvSource({
        "true,  false, true,  false",
        "true,  true,  true,  true",
        "false, false, false, false",
        "false, true,  false, true"})
    void fourCombinations(boolean blocking, boolean blockOnFailure, boolean pendingBlocks, boolean failedBlocks) {
        assertThat(QcBlockingPolicy.blocks("generated", "pending", blocking, blockOnFailure)).isEqualTo(pendingBlocks);
        assertThat(QcBlockingPolicy.blocks("generated", "failed", blocking, blockOnFailure)).isEqualTo(failedBlocks);
    }

    @Test
    @DisplayName("waived, passed and cancelled never block, whatever the flags")
    void terminalStatesNeverBlock() {
        for (String status : new String[] {"waived", "passed", "cancelled"}) {
            assertThat(QcBlockingPolicy.blocks("generated", status, true, true)).isFalse();
        }
    }

    @Test
    @DisplayName("manual and correction checks never block")
    void manualNeverBlocks() {
        assertThat(QcBlockingPolicy.blocks("manual", "pending", true, true)).isFalse();
        assertThat(QcBlockingPolicy.blocks("manual", "failed", true, true)).isFalse();
        assertThat(QcBlockingPolicy.blocks("correction", "pending", true, true)).isFalse();
        assertThat(QcBlockingPolicy.canBlock(false, false)).isFalse();
        assertThat(QcBlockingPolicy.canBlock(false, true)).isTrue();
    }
}
