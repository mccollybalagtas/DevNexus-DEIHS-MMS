package com.deihs.mms.model;

/**
 * A selectable reference row (category, facility, equipment) for combo
 * boxes. The label is what the user reads; the id is what is saved.
 */
public record LookupOption(int id, String label) {

    @Override
    public String toString() {
        return label;
    }
}
