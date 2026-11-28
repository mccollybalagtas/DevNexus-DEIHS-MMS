package com.deihs.mms.model;
import java.time.LocalDateTime;
import java.util.List;
public record RequestChecklist(int id, int requestId, String name, LocalDateTime createdAt, List<RequestChecklistItem> items) {}
