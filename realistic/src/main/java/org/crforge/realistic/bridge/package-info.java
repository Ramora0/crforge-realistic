/*
 * crforge-realistic - the game's own look for crforge's battle simulator
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The few classes of crforge's desktop module the realistic view is built on, copied from
 * Ramora0/crforge (branch realistic-render, 7f6d6bcf) so that nothing here depends on crforge's
 * desktop module: {@link org.crforge.realistic.bridge.BattleSession} (a battle on the core and its
 * players' commands), {@link org.crforge.realistic.bridge.BattleAdapter} and {@link
 * org.crforge.realistic.bridge.BattleFrame} (the core read into what is drawn), {@link
 * org.crforge.realistic.bridge.EntityView}, {@link org.crforge.realistic.bridge.ReplayFile} and
 * {@link org.crforge.realistic.bridge.ReplayPlayer} (replays), {@link
 * org.crforge.realistic.bridge.ViewOrientation} (which side is at the bottom), {@link
 * org.crforge.realistic.bridge.DataSelection} (which game tables to open) and what they need.
 *
 * <p>They are crforge's code (Apache-2.0, see NOTICE) with this repository's changes: the package,
 * the model opponent left out of {@code BattleSession}, and the additions the realistic view made
 * to them on that branch (attack timers, unit motions, hero forms, the replay's player names). Each
 * keeps crforge's license header.
 */
package org.crforge.realistic.bridge;
