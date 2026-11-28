package com.deihs.mms.model;
import java.util.List;
public record ChecklistTemplate(int id, String name, String category, String description, boolean active, List<String> items) {}
