package com.example.crosspath.data;

/** Current-period counts only. Old retained histories never enter this summary. */
public final class NotificationDeliveryStatus {
    public final int pending;
    public final int posted;
    public final int blocked;
    public NotificationDeliveryStatus(int pending, int posted, int blocked) {
        this.pending = pending;
        this.posted = posted;
        this.blocked = blocked;
    }
}
