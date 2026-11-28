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
    @Test
    public void clearingASelectedRegionIsDifferentFromLeavingItBlank() {
        Function<String, Object> region = text -> text.isEmpty() ? JSONObject.NULL : Long.parseLong(text);
        assertSame(JSONObject.NULL, submittedValue("2", "", region));
        assertNull(submittedValue(null, "", region));
        assertNull(submittedValue("", "", region));
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
