package com.deihs.mms.model;
import java.time.LocalDateTime;
public record RequestChecklistItem(int id, int checklistId, String text, boolean done, String doneBy, LocalDateTime doneAt, int sortOrder) {}
