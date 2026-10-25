package com.fitledger.domain

/** Only forgive floating-point addition noise; a zero quota is always exact. */
object QuotaMath {
    fun within(value: Double, limit: Double): Boolean {
        if (!value.isFinite() || !limit.isFinite() || value < 0 || limit < 0) return false
        if (value <= limit) return true
        if (limit == 0.0) return false
        val tolerance = minOf(Math.ulp(limit) * 4.0, limit * 1e-12)
        return value - limit <= tolerance
    }
}
