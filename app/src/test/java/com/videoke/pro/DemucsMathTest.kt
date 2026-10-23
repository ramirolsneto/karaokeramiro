package com.videoke.pro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemucsMathTest {
    @Test fun fixedModelTensorContractAndVocalStemIndex() {
        assertEquals(343980, DemucsSeparator.SEGMENT)
        assertEquals(85995, DemucsSeparator.OVERLAP)
        assertEquals(257985, DemucsSeparator.STRIDE)
        assertEquals(3, DemucsSeparator.VOCALS_INDEX)
        assertEquals(3 * 2 * 343980 + 343980 + 17, DemucsMath.vocalOffset(1, 17))
    }

    @Test fun chunkCountCoversShortAndBoundaryLengthInputs() {
        assertEquals(1, DemucsMath.chunkCount(1))
        assertEquals(1, DemucsMath.chunkCount(DemucsSeparator.STRIDE.toLong()))
        assertEquals(2, DemucsMath.chunkCount(DemucsSeparator.STRIDE.toLong() + 1))
    }

    @Test fun overlapWindowHasComplementaryEdges() {
        val w = DemucsMath.window()
        assertEquals(0f, w[0], 1e-6f)
        assertEquals(1f, w[DemucsSeparator.OVERLAP - 1], 1e-6f)
        for (i in 0 until DemucsSeparator.OVERLAP)
            assertEquals(1f, w[i] + w[DemucsSeparator.STRIDE + i], 2e-5f)
    }
}
