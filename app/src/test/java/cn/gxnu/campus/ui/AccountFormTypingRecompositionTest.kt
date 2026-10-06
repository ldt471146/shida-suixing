package cn.gxnu.campus.ui

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import androidx.compose.runtime.structuralEqualityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the invalidation scope of a keystroke in the account form.
 *
 * A keystroke writes only the field's own text state, so only the restart scope that reads it may
 * be invalidated. The form scope reads the derived error states instead of the text states, and a
 * derived state only invalidates its readers when its value actually changes.
 *
 * Compose invalidates restart scopes from the reads recorded while a scope composes; this test
 * records the same reads with [SnapshotStateObserver], which is driven by the same snapshot apply
 * notifications as the composer. The same topology was run against a real
 * `Composition`/`ControlledComposition` with `Recomposer`-style driving, and both paths invalidate
 * the same scopes for every case below (composer needs an Android runtime, hence the observer
 * based check here).
 */
class AccountFormTypingRecompositionTest {

    /** Stand-in for a restart scope: reads are re-recorded after every change, like a recomposition. */
    private class ScopeRecorder {
        private val observer = SnapshotStateObserver { block -> block() }
        private val scopes = mutableListOf<Scope>()
        val invalidations = linkedMapOf<String, Int>()

        private class Scope(val name: String, val reads: () -> Unit)

        fun scope(name: String, reads: () -> Unit) {
            invalidations[name] = 0
            scopes += Scope(name, reads)
        }

        fun start() {
            observer.start()
            record()
        }

        fun stop() = observer.stop()

        /** Applies a write the way a keystroke does and lets the runtime deliver invalidations. */
        fun change(write: () -> Unit) {
            Snapshot.withMutableSnapshot(write)
            Snapshot.sendApplyNotifications()
            record()
        }

        private fun record() {
            scopes.forEach { scope ->
                // Recomposing a scope drops the reads it no longer performs and records the ones it does.
                observer.clear(scope.name)
                observer.observeReads(scope.name, { name -> invalidations[name] = (invalidations[name] ?: 0) + 1 }) {
                    scope.reads()
                }
            }
        }
    }

    private class AccountForm {
        val account = mutableStateOf("")
        val submitted = mutableStateOf(false)
        val saveError = mutableStateOf<String?>(null)
        val accountError = derivedStateOf(structuralEqualityPolicy()) { submitted.value && account.value.isBlank() }
        val passwordError = derivedStateOf(structuralEqualityPolicy()) { submitted.value && account.value.isEmpty() }
    }

    /** Typing a character, exactly like AccountInputField's onValueChange does. */
    private fun type(form: AccountForm, text: String) {
        form.account.value = text
        form.saveError.value = null
    }

    @Test
    fun aKeystrokeInvalidatesOnlyTheFieldScope() {
        val form = AccountForm()
        val recorder = ScopeRecorder()
        // AccountScreen: the form scope reads the derived error states and the save error text.
        recorder.scope("form") {
            observed = form.accountError.value to form.passwordError.value
            observed = form.saveError.value
        }
        // AccountInputField: the field scope reads the text state it renders.
        recorder.scope("field") {
            observed = form.account.value
        }
        recorder.start()
        try {
            repeat(10) { index -> recorder.change { type(form, "user$index") } }
        } finally {
            recorder.stop()
        }
        assertEquals("the form must not be invalidated while typing", 0, recorder.invalidations["form"])
        assertEquals("the field is the only scope a keystroke invalidates", 10, recorder.invalidations["field"])
    }

    @Test
    fun theFormScopeIsInvalidatedWhenTheTextStateIsReadThere() {
        val form = AccountForm()
        val recorder = ScopeRecorder()
        // Counterfactual: a form scope that reads the text state directly, the shape this design avoids.
        recorder.scope("form") {
            observed = form.account.value
        }
        recorder.start()
        try {
            repeat(10) { index -> recorder.change { type(form, "user$index") } }
        } finally {
            recorder.stop()
        }
        assertEquals("reading the text state in the form scope recomposes the whole form", 10, recorder.invalidations["form"])
    }

    @Test
    fun theErrorsInvalidateTheFormScopeOnlyWhenTheirValueChanges() {
        val form = AccountForm()
        val recorder = ScopeRecorder()
        recorder.scope("form") {
            observed = form.accountError.value
        }
        recorder.scope("field") {
            observed = form.account.value
        }
        recorder.start()
        try {
            recorder.change { form.submitted.value = true }
            assertEquals("blank account after submit shows the error", 1, recorder.invalidations["form"])
            recorder.change { type(form, "a") }
            assertEquals("the first character clears the error", 2, recorder.invalidations["form"])
            repeat(5) { index -> recorder.change { type(form, "ab$index") } }
            assertEquals("further characters keep the error value", 2, recorder.invalidations["form"])
        } finally {
            recorder.stop()
        }
    }

    /**
     * Material3's `OutlinedTextFieldDefaults.colors(...)` builds a fresh `TextFieldColors` on every
     * call, so building it inside the field would allocate one per keystroke. Passing it in keeps
     * the per-keystroke arguments of the field unchanged.
     */
    @Test
    fun theFieldReceivesItsColorsInsteadOfBuildingThemPerKeystroke() {
        val field = Class.forName("cn.gxnu.campus.ui.screens.AccountScreenKt")
            .declaredMethods
            .single { method -> method.name == "AccountInputField" }
        assertTrue(
            "AccountInputField must receive its TextFieldColors from the screen scope",
            field.parameterTypes.any { type -> type.name == "androidx.compose.material3.TextFieldColors" }
        )
    }

    private var observed: Any? = null
}
