// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

/* This file is part of the dynarmic project.
 * Copyright (c) 2022 MerryMage
 * SPDX-License-Identifier: 0BSD
 */

#include "dynarmic/interface/exclusive_monitor.h"

#include <algorithm>
#include <atomic>

#include "common/assert.h"

namespace Dynarmic {

ExclusiveMonitor::ExclusiveMonitor(std::size_t processor_count)
        : reservations(processor_count) {}

size_t ExclusiveMonitor::GetProcessorCount() const {
    return reservations.size();
}

void ExclusiveMonitor::Lock() {
    lock.Lock();
}

void ExclusiveMonitor::Unlock() {
    lock.Unlock();
}

bool ExclusiveMonitor::CheckAndClear(std::size_t processor_id, VAddr address) {
    const VAddr masked_address = address & RESERVATION_GRANULE_MASK;

#ifdef ARCHITECTURE_arm64
    VAddr* own = &reservations[processor_id].address;
    VAddr expected = masked_address;
    if (__atomic_load_n(own, __ATOMIC_RELAXED) != masked_address ||
        !__atomic_compare_exchange_n(own, &expected, INVALID_EXCLUSIVE_ADDRESS, false,
                                     __ATOMIC_ACQ_REL, __ATOMIC_RELAXED)) {
        return false;
    }
    for (Reservation& reservation : reservations) {
        if (__atomic_load_n(&reservation.address, __ATOMIC_RELAXED) == masked_address) {
            expected = masked_address;
            __atomic_compare_exchange_n(&reservation.address, &expected, INVALID_EXCLUSIVE_ADDRESS, false,
                                        __ATOMIC_ACQ_REL, __ATOMIC_RELAXED);
        }
    }
    return true;
#endif

    Lock();
    if (reservations[processor_id].address != masked_address) {
        Unlock();
        return false;
    }

    for (Reservation& other : reservations) {
        if (other.address == masked_address) {
            other.address = INVALID_EXCLUSIVE_ADDRESS;
        }
    }
    return true;
}

void ExclusiveMonitor::Clear() {
#ifdef ARCHITECTURE_arm64
    for (Reservation& other : reservations) {
        __atomic_store_n(&other.address, INVALID_EXCLUSIVE_ADDRESS, __ATOMIC_RELEASE);
    }
#else
    Lock();
    for (Reservation& other : reservations) {
        other.address = INVALID_EXCLUSIVE_ADDRESS;
    }
    Unlock();
#endif
}

void ExclusiveMonitor::ClearProcessor(std::size_t processor_id) {
#ifdef ARCHITECTURE_arm64
    __atomic_store_n(&reservations[processor_id].address, INVALID_EXCLUSIVE_ADDRESS, __ATOMIC_RELEASE);
#else
    Lock();
    reservations[processor_id].address = INVALID_EXCLUSIVE_ADDRESS;
    Unlock();
#endif
}

}  // namespace Dynarmic
