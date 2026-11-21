package com.example.engine.filter

import java.util.Locale

/**
 * Memory-efficient, thread-safe-on-publish Reversed-Domain Trie.
 * Stores labels in reverse order (e.g., "ads.example.com" -> ["com", "example", "ads"])
 * so both exact domains and all subdomains match in O(label_count) time without linear scanning.
 */
class ReversedDomainTrie {

    private class Node {
        var mask: Int = 0
        var customFlag: Boolean = false
        var children: HashMap<String, Node>? = null
    }

    private val root = Node()
    var uniqueDomainCount: Int = 0
        private set

    fun insert(domain: String, mask: Int, isCustom: Boolean = false): Boolean {
        val normalized = normalizeDomain(domain) ?: return false
        val labels = normalized.split('.')
        var current = root
        for (i in labels.size - 1 downTo 0) {
            val label = labels[i]
            val map = current.children ?: HashMap<String, Node>(2).also { current.children = it }
            var next = map[label]
            if (next == null) {
                next = Node()
                map[label] = next
            }
            current = next
        }
        val wasNew = current.mask == 0 && !current.customFlag
        current.mask = current.mask or mask
        if (isCustom) current.customFlag = true
        if (wasNew) uniqueDomainCount++
        return true
    }

    /**
     * Checks if [domain] or any of its parent domains exist in the trie.
     * Returns a packed match result: bitmask of matched categories and whether a custom rule matched.
     */
    fun match(domain: String): MatchResult? {
        val normalized = normalizeDomain(domain) ?: return null
        val labels = normalized.split('.')
        var current = root
        var accumulatedMask = 0
        var customMatched = false

        for (i in labels.size - 1 downTo 0) {
            val children = current.children ?: break
            val next = children[labels[i]] ?: break
            current = next
            if (current.mask != 0) {
                accumulatedMask = accumulatedMask or current.mask
            }
            if (current.customFlag) {
                customMatched = true
            }
        }
        return if (accumulatedMask != 0 || customMatched) {
            MatchResult(mask = accumulatedMask, isCustom = customMatched)
        } else {
            null
        }
    }

    data class MatchResult(val mask: Int, val isCustom: Boolean)

    companion object {
        private val LABEL_REGEX = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")
        private val IPV4_REGEX = Regex("^(\\d{1,3}\\.){3}\\d{1,3}$")

        /**
         * Parses hosts-file lines, AdGuard `||domain^` lines, wildcard `*.domain`, or URLs
         * into a clean lowercase domain name, returning null if malformed or invalid.
         */
        fun normalizeDomain(rawInput: String): String? {
            var s = rawInput.trim()
            if (s.isEmpty() || s.startsWith("#") || s.startsWith("!") || s.startsWith("//")) {
                return null
            }

            // Strip inline comments
            val hashIdx = s.indexOf('#')
            if (hashIdx >= 0) s = s.substring(0, hashIdx).trim()
            if (s.isEmpty()) return null

            // Handle hosts format: "0.0.0.0 example.com" or "127.0.0.1 example.com"
            if (s.startsWith("0.0.0.0 ") || s.startsWith("0.0.0.0\t") ||
                s.startsWith("127.0.0.1 ") || s.startsWith("127.0.0.1\t") ||
                s.startsWith("::1 ") || s.startsWith(":: ")
            ) {
                val parts = s.split(Regex("\\s+"))
                if (parts.size < 2) return null
                s = parts[1].trim()
            }

            // Handle AdGuard syntax: "||example.com^" or "@@||example.com^"
            if (s.startsWith("@@")) s = s.removePrefix("@@")
            if (s.startsWith("||")) s = s.removePrefix("||")
            val caretIdx = s.indexOf('^')
            if (caretIdx >= 0) s = s.substring(0, caretIdx)
            val dollarIdx = s.indexOf('$')
            if (dollarIdx >= 0) s = s.substring(0, dollarIdx)

            // Handle URL scheme & path
            val schemeIdx = s.indexOf("://")
            if (schemeIdx >= 0) {
                s = s.substring(schemeIdx + 3)
            }
            val slashIdx = s.indexOf('/')
            if (slashIdx >= 0) s = s.substring(0, slashIdx)
            val queryIdx = s.indexOf('?')
            if (queryIdx >= 0) s = s.substring(0, queryIdx)

            // Strip port if present
            val colonIdx = s.indexOf(':')
            if (colonIdx >= 0) s = s.substring(0, colonIdx)

            // Strip wildcard prefix and trailing dot
            s = s.removePrefix("*.").removePrefix(".").removeSuffix(".").lowercase(Locale.ROOT).trim()

            if (!isValidHostname(s)) return null
            return s
        }

        fun isValidHostname(domain: String): Boolean {
            if (domain.length !in 3..253) return false
            if (domain == "localhost" || domain.endsWith(".localhost") || domain.endsWith(".local")) return false
            if (IPV4_REGEX.matches(domain)) return false
            val labels = domain.split('.')
            if (labels.size < 2) return false
            // TLD cannot be purely numeric
            if (labels.last().all { it.isDigit() }) return false
            return labels.all { label -> LABEL_REGEX.matches(label) }
        }
    }
}
