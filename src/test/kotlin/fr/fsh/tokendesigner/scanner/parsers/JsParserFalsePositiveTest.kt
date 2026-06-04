package fr.fsh.tokendesigner.scanner.parsers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for issue #24 — the TS/JS indexer must not greedily index
 * standard application objects (JSON-Schema bodies, enum maps, Storybook
 * stories) as token dictionaries.
 */
class JsParserFalsePositiveTest {

    @Test
    fun `json-schema file is detected as NONE`() {
        val src = """
            export const WIDGET_LAYOUT_SCHEMA = {
              ${"$"}schema: 'http://json-schema.org/draft-07/schema#',
              ${"$"}id: 'wms-widget-layout',
              type: 'object',
              properties: {
                rows: { type: 'array', items: { ${"$"}ref: '#/${"$"}defs/LayoutRow' } },
              },
              ${"$"}defs: {
                WidgetSlot: {
                  type: 'object',
                  properties: { flex: { type: 'number', minimum: 0 } },
                },
              },
            } as const;
        """.trimIndent()

        assertEquals(JsTokenFileParserRegistry.Mode.NONE, JsTokenFileParserRegistry.detectMode(src))
        assertTrue(JsTokenFileParserRegistry.parseFull(src).leaves.isEmpty())
    }

    @Test
    fun `event-name enum yields no tokens`() {
        val src = """
            export const ENTITY_EVENTS = {
              CREATED:        'ENTITY_CREATED',
              UPDATED:        'ENTITY_UPDATED',
              DELETED:        'ENTITY_DELETED',
            } as const;

            export const STATS_REFRESH = 'STATS_REFRESH';
        """.trimIndent()

        // No schema / storybook / RN hints → Style-Dictionary mode, but the
        // object is rejected by the value classifier.
        assertTrue(JsTokenFileParserRegistry.parseFull(src).leaves.isEmpty())
    }

    @Test
    fun `storybook import is detected as NONE`() {
        val src = """
            import type { Meta, StoryObj } from '@storybook/angular';
            export const Primary: StoryObj<ButtonComponent> = { args: { label: 'Button' } };
        """.trimIndent()

        assertEquals(JsTokenFileParserRegistry.Mode.NONE, JsTokenFileParserRegistry.detectMode(src))
    }

    @Test
    fun `genuine style-dictionary preset is still indexed`() {
        val src = """
            export const tokens = {
              primitive: {
                primary: { 500: '#FE5716' },
                neutral: { 700: '#1a1a1a' },
              },
              semantic: { surface: '{primitive.neutral.700}' },
            };
        """.trimIndent()

        val leaves = JsTokenFileParserRegistry.parseFull(src).leaves
        assertTrue(leaves.any { it.path == "primitive.primary.500" && it.value == "#FE5716" })
        assertTrue(leaves.any { it.path == "semantic.surface" && it.value == "{primitive.neutral.700}" })
    }

    @Test
    fun `runtime theme bag is still indexed`() {
        // The react-native import forces RUNTIME mode; untyped bags keep their
        // binding prefix in the emitted path.
        val src = """
            import { StyleSheet } from 'react-native';
            const colors = { PRIMARY_500: '#FE5716', BG: '#ffffff' };
            export const radius = { sm: 8, md: 16 };
        """.trimIndent()

        val leaves = JsTokenFileParserRegistry.parseFull(src).leaves
        assertTrue(leaves.any { it.path == "colors.PRIMARY_500" && it.value == "#FE5716" })
        // Unitless numeric scale survives the classifier.
        assertTrue(leaves.any { it.path == "radius.sm" && it.value == "8" })
    }

    @Test
    fun `status-config map keeps colours but drops labels and variant names`() {
        // A Record keyed by enum value: each entry mixes real colours
        // (bg / color) with non-token metadata (label / variant). Only the
        // colours must survive. See issue #24 (follow-up).
        val src = """
            export const HANDLING_UNIT_STATUS_CONFIG: Record<HandlingUnitStatus, HandlingUnitStatusConfig> = {
              IN_STOCK:  { label: 'En stock',   variant: 'success', bg: '#dcfce7', color: '#15803d' },
              PICKING:   { label: 'En picking', variant: 'info',    bg: '#e8ecf5', color: '#4563a0' },
              INCIDENT:  { label: 'Incident',   variant: 'danger',  bg: '#fee2e2', color: '#b91c1c' },
            };
        """.trimIndent()

        val leaves = JsTokenFileParserRegistry.parseFull(src).leaves
        val paths = leaves.map { it.path }
        // The reported false positive must be gone.
        assertTrue("PICKING.label leaked: $paths", paths.none { it.endsWith(".label") })
        assertTrue("variant names leaked: $paths", paths.none { it.endsWith(".variant") })
        // The real colours are still indexed (binding prefix stripped → typed aggregator).
        assertTrue(leaves.any { it.value == "#4563a0" })
        assertTrue(leaves.any { it.value == "#dcfce7" })
    }

    @Test
    fun `token preset next to an enum keeps only the preset`() {
        // Style-Dictionary mode (no runtime hint) → binding prefix is stripped,
        // so we assert on values rather than paths.
        val src = """
            export const STATUS = { OK: 'STATUS_OK', KO: 'STATUS_KO' };
            export const colors = { brand: '#FE5716', ink: '#111111' };
        """.trimIndent()

        val leaves = JsTokenFileParserRegistry.parseFull(src).leaves
        assertTrue(leaves.any { it.value == "#FE5716" })
        assertTrue(leaves.none { it.value.startsWith("STATUS_") })
    }
}
