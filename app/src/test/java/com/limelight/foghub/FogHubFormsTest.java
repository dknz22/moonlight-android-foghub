package com.limelight.foghub;

import org.json.JSONObject;
import org.junit.Test;

import java.util.function.Function;

import static com.limelight.foghub.FogHubForms.submittedValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

public class FogHubFormsTest {
    private static final Function<String, Object> OPTIONAL_TEXT = text ->
            text.isEmpty() ? JSONObject.NULL : text;

    @Test
    public void clearingAnExistingValueIsDifferentFromLeavingAnOptionalFieldBlank() {
        assertSame(JSONObject.NULL, submittedValue("old contact", "", OPTIONAL_TEXT));
        assertNull(submittedValue(null, "", OPTIONAL_TEXT));
        assertNull(submittedValue("", "", OPTIONAL_TEXT));
        assertEquals(" new contact ", submittedValue("old contact", " new contact ", OPTIONAL_TEXT));
    }

    @Test
    public void savingOtherSettingsSkipsUnchangedInputsEvenIfTheirCurrentValueWouldFailValidation() {
        assertNull(submittedValue("", "", text -> { throw new AssertionError("Unchanged password"); }));
        assertNull(submittedValue("legacy", "legacy", Long::parseLong));
        assertThrows(NumberFormatException.class, () -> submittedValue(null, "", Long::parseLong));
    }

    @Test
    public void editedIntegersCompareByValueAndKeepTheirJsonType() {
        assertNull(submittedValue("12", "0012", Long::parseLong));
        assertEquals(0L, submittedValue("12", "0", Long::parseLong));
        assertEquals(12L, submittedValue(null, "12", Long::parseLong));
        assertThrows(NumberFormatException.class, () -> submittedValue("12", "1.2", Long::parseLong));
    }
}
