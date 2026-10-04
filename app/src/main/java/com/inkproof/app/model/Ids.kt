package com.inkproof.app.model

import java.util.UUID

/** Every entity in InkProof has a globally unique, persistent ID. */
fun newId(): String = UUID.randomUUID().toString()
