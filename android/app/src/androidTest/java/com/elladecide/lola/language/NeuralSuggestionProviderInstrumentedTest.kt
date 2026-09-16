package com.elladecide.lola.language

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NeuralSuggestionProviderInstrumentedTest {

    @Test
    fun v3ModelRanksTargetCasesOnDevice() {
        NeuralSuggestionProvider(ApplicationProvider.getApplicationContext()).use { provider ->
            val cases = listOf(
                Triple("ouedo", "quiero", "puedo"),
                Triple("xausqr", "quiero", "causar"),
                Triple("juefos", "quiero", "juegos"),
            )

            cases.forEach { (typo, context, expected) ->
                assertEquals(expected, provider.suggest(typo, context, limit = 3).firstOrNull())
            }
        }
    }
}
