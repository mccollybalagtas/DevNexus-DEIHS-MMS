package com.deihs.mms.model;

/**
 * A combo box entry backed by an enum value. The label is what the user
 * reads; the value is what gets stored. A null value means "no filter".
 */
public record Choice(String value, String label) {

    @Override
    public String toString() {
        return label;
    }
}
