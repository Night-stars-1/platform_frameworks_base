/*
 * Copyright (C) 2026 The uwuAOSP Project
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.clipboard;

import java.util.HashMap;
import java.util.Map;

/** In-memory, operation-specific prompt state. Accessed under ClipboardService's lock. */
final class ClipboardAccessState {
    static final long DECISION_DURATION_MILLIS = 30_000L;
    static final long PENDING_DURATION_MILLIS = 120_000L;

    enum Decision { NONE, ALLOW, DENY }

    private final Map<String, Long> mLastPrompts = new HashMap<>();
    private final Map<String, TemporaryDecision> mDecisions = new HashMap<>();
    private final Map<String, PendingPrompt> mPendingPrompts = new HashMap<>();
    private long mNextPromptId;

    Decision getDecision(String key, long now) {
        final TemporaryDecision decision = mDecisions.get(key);
        if (decision == null) {
            return Decision.NONE;
        }
        if (now >= decision.expiresAt) {
            mDecisions.remove(key);
            return Decision.NONE;
        }
        return decision.allowed ? Decision.ALLOW : Decision.DENY;
    }

    boolean beginPrompt(String key, long now) {
        final Long lastPrompt = mLastPrompts.get(key);
        if (getPendingPrompt(key, now) != null
                || (lastPrompt != null && now - lastPrompt < DECISION_DURATION_MILLIS)) {
            return false;
        }
        mLastPrompts.put(key, now);
        mPendingPrompts.put(key, new PendingPrompt(++mNextPromptId,
                now + PENDING_DURATION_MILLIS));
        return true;
    }

    PendingPrompt getPendingPrompt(String key, long now) {
        final PendingPrompt prompt = mPendingPrompts.get(key);
        if (prompt != null && now >= prompt.expiresAt) {
            mPendingPrompts.remove(key);
            return null;
        }
        return prompt;
    }

    boolean isCurrentPrompt(String key, long promptId, long now) {
        final PendingPrompt prompt = getPendingPrompt(key, now);
        return prompt != null && prompt.id == promptId;
    }

    void resolvePrompt(String key, boolean allowed, boolean persist, long now) {
        mPendingPrompts.remove(key);
        mLastPrompts.put(key, now);
        if (persist) {
            mDecisions.remove(key);
        } else {
            mDecisions.put(key, new TemporaryDecision(allowed,
                    now + DECISION_DURATION_MILLIS));
        }
    }

    void cancelLaunch(String key) {
        mPendingPrompts.remove(key);
    }

    /** Rules changed: retain open prompts, but discard temporary choices and cooldowns. */
    void invalidateDecisions(int userId, int operation) {
        mDecisions.keySet().removeIf(key -> matches(key, userId, operation));
        mLastPrompts.keySet().removeIf(key -> matches(key, userId, operation));
    }

    void clearUser(int userId) {
        invalidateDecisions(userId, -1);
        mPendingPrompts.keySet().removeIf(key -> matches(key, userId, -1));
    }

    private static boolean matches(String key, int userId, int operation) {
        return (userId < 0 || key.startsWith(userId + ":"))
                && (operation < 0 || key.endsWith(":" + operation));
    }

    static final class PendingPrompt {
        final long id;
        final long expiresAt;

        PendingPrompt(long id, long expiresAt) {
            this.id = id;
            this.expiresAt = expiresAt;
        }
    }

    private static final class TemporaryDecision {
        final boolean allowed;
        final long expiresAt;

        TemporaryDecision(boolean allowed, long expiresAt) {
            this.allowed = allowed;
            this.expiresAt = expiresAt;
        }
    }
}
