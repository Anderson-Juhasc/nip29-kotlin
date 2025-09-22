package com.nostr

import com.nostr.core.Event
import com.nostr.core.KeyPair
import kotlinx.serialization.json.*
import java.time.Instant
import java.lang.System

/**
 * NIP-29 compliant group client for relay-based groups
 */
object Nip29 {

  // ========== CORE HELPERS ==========

  private fun buildTags(
    groupId: String,
    useTagD: Boolean = false,
    extra: List<List<String>> = emptyList()
  ): List<List<String>> =
    mutableListOf<List<String>>().apply {
      add(listOf(if (useTagD) "d" else "h", groupId))
      addAll(extra)
    }

  private fun event(
    kind: Int,
    groupId: String,
    keyPair: KeyPair,
    content: String = "",
    tags: List<List<String>> = emptyList(),
    useTagD: Boolean = false
  ): JsonElement {
    val e = Event(
      pubkey = keyPair.publicKeyHex,
      createdAt = Instant.now().epochSecond,
      kind = kind,
      tags = buildTags(groupId, useTagD, tags),
      content = content
    ).sign(keyPair)

    return buildJsonArray {
      add("EVENT")
      add(e.toJsonObject())
    }
  }

  private fun query(
    kinds: List<Int>,
    limit: Int = 50,
    subscriptionId: String = "sub-${System.currentTimeMillis()}",
    tags: Map<String, List<String>> = emptyMap(),
    authors: List<String>? = null,
    since: Long? = null,
    until: Long? = null
  ): JsonElement {
    return buildJsonArray {
      add("REQ")
      add(subscriptionId)
      add(buildJsonObject {
        put("kinds", buildJsonArray { kinds.forEach { add(it) } })
        tags.forEach { (k, v) -> put("#$k", buildJsonArray { v.forEach { add(it) } }) }
        authors?.let { put("authors", buildJsonArray { it.forEach { add(it) } }) }
        since?.let { put("since", it) }
        until?.let { put("until", it) }
        put("limit", limit)
      })
    }
  }

  // ========== USER MANAGEMENT EVENTS ==========

  /**
   * Request to join a group (kind:9021)
   */
  fun requestJoinGroup(keyPair: KeyPair, groupId: String, reason: String? = null, inviteCode: String? = null) =
    event(9021, groupId, keyPair, reason.orEmpty(),
      inviteCode?.let { listOf(listOf("code", it)) } ?: emptyList()
    )

  /**
   * Request to leave a group (kind:9022)
   */
  fun requestLeaveGroup(keyPair: KeyPair, groupId: String, reason: String? = null) =
    event(9022, groupId, keyPair, reason.orEmpty())

  // ========== MODERATION EVENTS (kinds:9000-9020) ==========

  /**
   * Add user to group (kind:9000)
   */
  fun addUser(keyPair: KeyPair, groupId: String, targetPubkey: String, roles: List<String> = emptyList(), reason: String? = null) =
    event(9000, groupId, keyPair, reason.orEmpty(), listOf(listOf("p", targetPubkey) + roles))

  /**
   * Remove user from group (kind:9001)
   */
  fun removeUser(keyPair: KeyPair, groupId: String, targetPubkey: String, reason: String? = null) =
    event(9001, groupId, keyPair, reason.orEmpty(), listOf(listOf("p", targetPubkey)))

  /**
   * Edit group metadata (kind:9002)
   */
  fun editGroupMetadata(
    keyPair: KeyPair,
    groupId: String,
    name: String? = null,
    about: String? = null,
    picture: String? = null,
    isPublic: Boolean? = null,
    isOpen: Boolean? = null,
    reason: String? = null
  ): JsonElement {
    val tags = mutableListOf<List<String>>().apply {
      name?.let { add(listOf("name", it)) }
      about?.let { add(listOf("about", it)) }
      picture?.let { add(listOf("picture", it)) }
      isPublic?.let { add(listOf(if (it) "public" else "private")) }
      isOpen?.let { add(listOf(if (it) "open" else "closed")) }
    }
    return event(9002, groupId, keyPair, reason.orEmpty(), tags)
  }

  /**
   * Delete event (kind:9005)
   */
  fun deleteEvent(keyPair: KeyPair, groupId: String, eventId: String, reason: String? = null) =
    event(9005, groupId, keyPair, reason.orEmpty(), listOf(listOf("e", eventId)))

  /**
   * Create group (kind:9007)
   */
  fun createGroup(keyPair: KeyPair, groupId: String, reason: String? = null) =
    event(9007, groupId, keyPair, reason.orEmpty())

  /**
   * Delete group (kind:9008)
   */
  fun deleteGroup(keyPair: KeyPair, groupId: String, reason: String? = null) =
    event(9008, groupId, keyPair, reason.orEmpty())

  /**
   * Create invite (kind:9009)
   */
  fun createInvite(
    keyPair: KeyPair,
    groupId: String,
    inviteCode: String? = null,
    reason: String? = null,
    maxUses: Int? = null,
    expiryHours: Int? = null
  ): JsonElement {
    val tags = mutableListOf<List<String>>().apply {
      add(listOf("code", inviteCode ?: generateInviteCode()))
      maxUses?.let { add(listOf("max_uses", it.toString())) }
      expiryHours?.let {
        val expiry = (System.currentTimeMillis() / 1000) + (it * 3600)
        add(listOf("expiry", expiry.toString()))
      }
    }
    return event(9009, groupId, keyPair, reason.orEmpty(), tags)
  }

  // ========== NORMAL USER EVENTS ==========

  /**
   * Send a message to group (kind:9 - short text note)
   */
  fun sendMessage(keyPair: KeyPair, groupId: String, content: String) =
    event(9, groupId, keyPair, content)

  /**
   * Reply to a message in group (kind:9)
   */
  fun replyMessage(keyPair: KeyPair, groupId: String, content: String, replyTo: String, rootId: String? = null): JsonElement {
    val tags = mutableListOf<List<String>>().apply {
      if (rootId != null && rootId != replyTo) {
        add(listOf("e", rootId, "", "root"))
        add(listOf("e", replyTo, "", "reply"))
      } else add(listOf("e", replyTo, "", "reply"))
    }
    return event(9, groupId, keyPair, content, tags)
  }

  /**
   * React to a message (kind:7)
   */
  fun reactToMessage(keyPair: KeyPair, groupId: String, targetId: String, reaction: String = "+") =
    event(7, groupId, keyPair, reaction, listOf(listOf("e", targetId), listOf("k", "9")))

  /**
   * Delete own message (kind:5)
   */
  fun deleteMessage(keyPair: KeyPair, groupId: String, messageId: String, reason: String? = null) =
    event(5, groupId, keyPair, reason.orEmpty(), listOf(listOf("e", messageId)))

  // ========== QUERIES FOR NIP-29 EVENTS ==========

  /**
   * Get group messages (kind:9)
   */
  fun getGroupMessages(groupId: String, limit: Int = 50) =
    query(listOf(9), limit, tags = mapOf("h" to listOf(groupId)))

  /**
   * Get message reactions (kind:7)
   */
  fun getMessageReactions(messageId: String) =
    query(listOf(7), 100, tags = mapOf("e" to listOf(messageId)))

  /**
   * Check if user is member (kind:9000, 9001)
   */
  fun isMember(groupId: String, pubkey: String) =
    query(listOf(9000, 9001), 50, tags = mapOf("h" to listOf(groupId), "p" to listOf(pubkey)))

  /**
   * Get group metadata (kind:39000)
   */
  fun getGroupMetadata(groupId: String) =
    query(listOf(39000), 1, tags = mapOf("d" to listOf(groupId)))

  /**
   * Get group admins (kind:39001)
   */
  fun getGroupAdmins(groupId: String) =
    query(listOf(39001), 1, tags = mapOf("d" to listOf(groupId)))

  /**
   * Get group members (kind:39002)
   */
  fun getGroupMembers(groupId: String) =
    query(listOf(39002), 1, tags = mapOf("d" to listOf(groupId)))

  /**
   * Get group roles (kind:39003)
   */
  fun getGroupRoles(groupId: String) =
    query(listOf(39003), 1, tags = mapOf("d" to listOf(groupId)))

  /**
   * Get moderation events for group (kinds:9000-9020)
   */
  fun getGroupModerationEvents(
    groupId: String,
    eventTypes: List<Int> = listOf(9000, 9001, 9002, 9005, 9007, 9008, 9009),
    limit: Int = 100
  ) = query(eventTypes, limit, tags = mapOf("h" to listOf(groupId)))

  /**
   * Get join/leave requests (kinds:9021, 9022)
   */
  fun getJoinLeaveRequests(groupId: String, limit: Int = 50) =
    query(listOf(9021, 9022), limit, tags = mapOf("h" to listOf(groupId)))

  /**
   * Custom query for any NIP-29 related events
   */
  fun queryCustom(
    kinds: List<Int>? = null,
    authors: List<String>? = null,
    since: Long? = null,
    until: Long? = null,
    limit: Int? = null,
    tags: Map<String, List<String>>? = null
  ): JsonElement {
    return buildJsonArray {
      add("REQ")
      add("custom-${System.currentTimeMillis()}")
      add(buildJsonObject {
        kinds?.let { put("kinds", buildJsonArray { it.forEach { add(it) } }) }
        authors?.let { put("authors", buildJsonArray { it.forEach { add(it) } }) }
        since?.let { put("since", it) }
        until?.let { put("until", it) }
        limit?.let { put("limit", it) }
        tags?.forEach { (k, v) -> put("#$k", buildJsonArray { v.forEach { add(it) } }) }
      })
    }
  }

  // ========== UTILS ==========

  private fun generateInviteCode(): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    return (1..8).map { chars.random() }.joinToString("")
  }
}
