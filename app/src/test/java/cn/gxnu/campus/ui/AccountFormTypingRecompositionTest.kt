package cn.gxnu.campus.ui

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import androidx.compose.runtime.structuralEqualityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
 *
 * The second half of the guard is structural: `AccountInputField` re-runs on every character, so
 * nothing it hands to `OutlinedTextField` may be built in its own body — neither the colours
 * (Material3's `colors()` allocates a fresh `TextFieldColors` per call) nor the text-field lambdas
 * (a fresh instance per composition defeats the argument's ability to compare equal). A JVM unit
 * test cannot compose the field, so those two arguments are asserted against the source, which is
 * what lets this file fail when the hoisting is removed.
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
        val password = mutableStateOf("")
        val submitted = mutableStateOf(false)
        val saveError = mutableStateOf<String?>(null)
        val accountError = derivedStateOf(structuralEqualityPolicy()) { submitted.value && account.value.isBlank() }
        val passwordError = derivedStateOf(structuralEqualityPolicy()) { submitted.value && password.value.isEmpty() }
    }

    /** Typing a character, exactly like AccountInputField's onValueChange does. */
    private fun type(form: AccountForm, text: String) {
        form.account.value = text
        form.saveError.value = null
    }

    /** Typing in the password field, whose onValueChange has the same shape. */
    private fun typePassword(form: AccountForm, text: String) {
        form.password.value = text
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
        recorder.scope("account field") {
            observed = form.account.value
        }
        recorder.scope("password field") {
            observed = form.password.value
        }
        recorder.start()
        try {
            repeat(10) { index -> recorder.change { type(form, "user$index") } }
        } finally {
            recorder.stop()
        }
        assertEquals("the form must not be invalidated while typing", 0, recorder.invalidations["form"])
        assertEquals("the field is the only scope a keystroke invalidates", 10, recorder.invalidations["account field"])
        assertEquals("typing an account must not touch the password field", 0, recorder.invalidations["password field"])
    }

    @Test
    fun aPasswordKeystrokeInvalidatesOnlyThePasswordFieldScope() {
        val form = AccountForm()
        val recorder = ScopeRecorder()
        recorder.scope("form") {
            observed = form.accountError.value to form.passwordError.value
            observed = form.saveError.value
        }
        recorder.scope("account field") {
            observed = form.account.value
        }
        recorder.scope("password field") {
            observed = form.password.value
        }
        recorder.start()
        try {
            repeat(10) { index -> recorder.change { typePassword(form, "pw$index") } }
        } finally {
            recorder.stop()
        }
        assertEquals("the form must not be invalidated while typing", 0, recorder.invalidations["form"])
        assertEquals("the field is the only scope a keystroke invalidates", 10, recorder.invalidations["password field"])
        assertEquals("typing a password must not touch the account field", 0, recorder.invalidations["account field"])
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

    /**
     * The compiler half of the colour argument: `TextFieldColors` compares by value, so even a
     * rebuilt instance is not a change as far as the field's arguments are concerned. If Material3
     * ever drops that, the colours have to be hoisted into a `remember`, not merely out of the field.
     */
    @Test
    fun theFieldColoursCompareByValue() {
        val colors = Class.forName("androidx.compose.material3.TextFieldColors")
        assertTrue(
            "TextFieldColors must compare by value, or a rebuilt instance invalidates the field's arguments",
            colors.declaredMethods.any { method -> method.name == "equals" && method.parameterCount == 1 }
        )
    }

    /**
     * The colours are built in exactly one place: the declaration of `accountFieldColors` plus the
     * single call in the screen scope. A second call would mean a field is building its own.
     */
    @Test
    fun theFieldColoursAreBuiltOnlyOnceOutsideTheField() {
        assertEquals(
            "the field colours must be built in exactly one call site, outside AccountInputField",
            2, Regex("accountFieldColors\\(").findAll(accountScreenSource()).count()
        )
    }

    /**
     * Every lambda handed to `OutlinedTextField` must be a value held across recompositions, not a
     * literal rebuilt inside `AccountInputField`'s restart scope. This is the source-level half of
     * the guard: a JVM test cannot compose the field to observe the lambda identity directly.
     */
    @Test
    fun theFieldArgumentsAreRememberedInsteadOfRebuiltPerKeystroke() {
        val body = functionBody("AccountInputField")
        assertFalse(
            "AccountInputField must not build the field colours in its own restart scope",
            body.contains("accountFieldColors(") || body.contains("OutlinedTextFieldDefaults.colors(")
        )
        assertTrue("AccountInputField must still render an OutlinedTextField", body.contains("OutlinedTextField("))
        // Only the arguments of the call itself: the remembers above it declare the same names.
        val call = body.substringAfter("OutlinedTextField(")
        for (argument in listOf("onValueChange", "placeholder", "supportingText", "trailingIcon")) {
            val value = argumentValue(call, argument)
            assertTrue(
                "$argument must be handed over as a remembered value, not built inline, was: $value",
                Regex("[A-Za-z_][A-Za-z0-9_]*").matches(value)
            )
        }
    }

    /** The right-hand side of `name = ...` inside the call, up to the end of the argument. */
    private fun argumentValue(call: String, name: String): String {
        val match = Regex("\\b$name\\s*=\\s*([^\\n,]+)").find(call)
            ?: throw AssertionError("AccountInputField passes no $name to OutlinedTextField")
        return match.groupValues[1].trim()
    }

    /** The text of one top-level function: its declaration to the closing brace in column 0. */
    private fun functionBody(name: String): String {
        val source = accountScreenSource()
        val declaration = Regex("fun\\s+$name\\s*\\(").find(source)
            ?: throw AssertionError("AccountScreen.kt has no function $name")
        val end = source.indexOf("\n}", declaration.range.first)
        return source.substring(declaration.range.first, if (end < 0) source.length else end)
    }

    /**
     * The guard reads [accountScreenSource], resolved from the test working directory (`app/` under
     * Gradle) or from the repository root, so it works from both entry points.
     */
    private fun accountScreenSource(): String {
        val relative = "src/main/java/cn/gxnu/campus/ui/screens/AccountScreen.kt"
        val candidates = generateSequence(File("").absoluteFile) { it.parentFile }
            .take(4)
            .flatMap { root -> sequenceOf(File(root, relative), File(root, "app/$relative")) }
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: throw AssertionError("AccountScreen.kt not found above ${File("").absolutePath}")
    }

    private var observed: Any? = null
}
