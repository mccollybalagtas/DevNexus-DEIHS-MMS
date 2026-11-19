package com.deihs.mms.model;

/**
 * The result of filing a new request: the database id and the ticket
 * number the requester will quote when following up.
 */
public record CreatedRequest(int id, String ticketNo) {
}
