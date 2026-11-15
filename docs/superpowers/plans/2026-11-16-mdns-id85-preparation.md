# mDNS dual stack and BMW ID8.5 preparation

**Goal:** Complete network-bound IPv4/IPv6 AirPlay discovery for every profile and align evidenced ID8.5 initialization differences with the BMW box.

**Constraints:** Preserve BMW's settings-controlled proactive connection, invitation gates, saved Wi-Fi policy, manual stop and pairing data. Keep ID9 and other profiles' Bluetooth message sequence unchanged. Do not commit or push.

## Implementation

- [x] Extend AirplayDnsRecords and AirplayServiceProbe: independent bound IPv4/IPv6 sockets, PTR→SRV/TXT→A/AAAA queries, multiple address candidates, TTL/cache-flush handling, bounded retries and cancellation cleanup. Add parser and transport failure tests before implementation.
- [x] Add multicast permission and scoped WifiManager multicast lock. Integrate discovered address alternatives into existing TCP candidate selection without changing activation policy.
- [x] ID8.5 only: send device-state greetings in box order 4E0C/4E0D/4E0A/4E09/4E0B before 5702. Preserve payloads and rescue behavior; test other profile isolation.
- [x] ID8.5 only: allow the existing bounded discoverability fallback before Wi-Fi when the selected paired receiver and passive listener are ready. Keep settings, authorization, cancellation and session ownership guards.
- [x] ID8.5 only: publish the local device name as the control-service instance, using a valid UTF-8 DNS-SD label; keep TXT id/srcvers and stable listener port. Test names and profile isolation.
- [x] Review changes, run JVM tests/lint, increment shared version once from HEAD 573/10669 to 574/10670, build release and install with adb -r on the connected phone. Record DiPlay verification and physical BMW verification limits.

## Evidence boundaries

Box-specific AT commands and video pre-record initialization do not directly map to Android APIs. Do not invent Bluetooth role/CoD changes, new authentication validation or new live video production before screen ownership from those observations. Document these differences rather than call them implemented equivalents.

