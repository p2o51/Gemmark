package com.gemmark.app.engine.schema

import com.google.mlkit.genai.schema.annotations.Generable
import com.google.mlkit.genai.schema.annotations.Guide

/**
 * Target schema for the STRUCTURED workload: the model must fill this class
 * from the frozen order-message prompt (constrained decoding, R41).
 * Field set mirrors the v1 JSON-task prompt so results stay comparable in
 * content, while validity is now guaranteed by the API instead of checked
 * after the fact.
 */
@Generable("Order details extracted from a customer message")
data class OrderInfo(
    @Guide(description = "Customer full name")
    val customer: String,
    @Guide(description = "Ordered items", minItems = 1, maxItems = 10)
    val items: List<OrderItem>,
    @Guide(description = "ISO currency name, e.g. euros")
    val currency: String,
    @Guide(description = "Requested delivery date as written in the message")
    val deliveryDate: String,
    @Guide(description = "Whether express shipping was requested")
    val express: Boolean,
)

@Generable("A single ordered item")
data class OrderItem(
    @Guide(description = "Item name")
    val name: String,
    @Guide(description = "Quantity ordered")
    val quantity: Int,
    @Guide(description = "Unit price as a number")
    val unitPrice: Double,
)
