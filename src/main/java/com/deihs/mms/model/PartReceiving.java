package com.deihs.mms.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PartReceiving(
        int id,
        int partId,
        String partName,
        int quantity,
        BigDecimal unitCost,
        String supplier,
        String referenceNo,
        String notes,
        String receivedBy,
        LocalDateTime receivedAt
) {}
