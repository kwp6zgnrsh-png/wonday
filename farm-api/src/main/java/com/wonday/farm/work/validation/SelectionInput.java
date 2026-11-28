package com.wonday.farm.work.validation;

import com.wonday.farm.work.dto.TermsVersionRequest;
import com.wonday.farm.work.exception.WorkSelectionException;

public final class SelectionInput {
    private SelectionInput() {}
    public static long id(String text) {
        try {
            if (text == null || !text.matches("[1-9][0-9]{0,18}")) throw WorkSelectionException.invalid();
            return Long.parseLong(text);
        } catch (NumberFormatException exception) { throw WorkSelectionException.invalid(); }
    }
    public static void require(long... ids) {
        for (long id : ids) if (id <= 0) throw WorkSelectionException.invalid();
    }
    public static int version(TermsVersionRequest request) {
        if (request == null || request.termsVersion() == null || request.termsVersion() <= 0) {
            throw WorkSelectionException.invalid();
        }
        return request.termsVersion();
    }
}
