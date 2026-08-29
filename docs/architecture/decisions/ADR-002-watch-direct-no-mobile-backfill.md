# ADR-002: Watch Direct does not backfill Mobile

## Status
Accepted

## Problem
Watch-direct and phone readings can form loops or silently change the phone's selected source.

## Decision
The G7 Watch Collector owns its local standalone reading pipeline. Watch-direct readings may be displayed on Wear surfaces but do not become an implicit Mobile source or backfill Mobile history.

## Rationale
This preserves source ownership and prevents circular synchronization.

## Consequences
Cross-device messages retain origin and are rejected when they violate ownership.

## Explicit non-goals
No automatic Watch-to-Mobile CGM fallback is introduced.

