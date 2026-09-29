#!/usr/bin/env python3
"""A Leon County SO CAD system, emitting dispatch records as they happen.

Writes one CSV row per involved person to stdout, in the shape the agency's export declares --
which is what the file drop already carries, because the transport is the only thing that differs
between a nightly export and a live feed (spec §4.3). demo/kafka/feed.sh pipes this into the
topic.

Three things are deliberate:

  * A fixed cast. Drawing a fresh person for every call produces a corpus in which nobody is ever
    named twice, and the association view the repository builds on co-occurrence has nothing to
    find. Real recurrence is sticky -- the same people, the same addresses, the same reporting
    parties -- and none of it survives independent sampling.

  * Addresses from the 13.6 reserved street pool, the same one the repository's own synthetic
    corpus uses. Nothing emitted here can collide with a real Florida address, and the repository's
    gazetteer can still resolve it to a block rather than dropping it on the county centroid.

  * One licence number, keyed inconsistently. UNK for a third of the cast who have none, and the
    rest punctuated three different ways at random. Identity resolution has to see through that,
    and a demo that never tries it has not shown anything.

Nothing here is random per run by default: --seed makes a session reproducible, which matters when
the point of the next run is to show the same thing again.
"""

import argparse
import random
import sys
import time
from datetime import datetime, timedelta

STREETS = ["Cinderpath Way", "Hollow Reed Ln", "Quarrystone Rd", "Fable Creek Dr",
           "Lantern Marsh Ct", "Pyrewood Ave", "Thistledown Blvd", "Veilstone Trce"]
FIRST = ["Alderic", "Brannock", "Corvane", "Delwyn", "Emberly", "Fenwick", "Gethin", "Halloran",
         "Ivorine", "Jessamy", "Kestrel", "Lowen", "Marrow", "Nevin", "Orlaith", "Pellam",
         "Quillon", "Rasmine", "Sable", "Thistle", "Umbrel", "Vesper", "Wrenna", "Yarrow"]
LAST = ["Ashgrove", "Bellhollow", "Cindermere", "Draymoor", "Everdell", "Fallowmede",
        "Hollowick", "Ironvale", "Jarrowfield", "Kilnbrook", "Larkspire", "Mosswater",
        "Northgale", "Oakenshaw", "Pyrefield", "Quarrymoor", "Rooksbridge", "Stonecarrow",
        "Thornbury", "Underhill", "Veilstone", "Wickersham", "Yewbank"]

# Weighted so the mix looks like a patrol shift rather than a uniform draw.
CALLS = (["BURG"] * 3 + ["THEFT"] * 5 + ["ASSLT"] * 3 + ["MVA"] * 4
         + ["DIST"] * 5 + ["SUSP"] * 4 + ["WELCK"] * 2)
BEATS = ["1A", "1B", "2A", "2B", "3A", "3B", "4A"]


def build_cast(r):
    cast = []
    for i in range(22):
        first, last = FIRST[i % len(FIRST)], LAST[i % len(LAST)]
        mid = r.choice(["", "", " " + chr(ord("A") + r.randint(0, 25))])
        dob = f"{r.randint(1,12):02d}/{r.randint(1,28):02d}/{r.randint(1962,2001)}"
        dl = (f"{chr(ord('A')+r.randint(0,25))}{r.randint(100,999)}-{r.randint(1000,9999)}"
              if i % 3 else None)
        cast.append({"name": f"{last.upper()}, {first.upper()}{mid.upper()}",
                     "dob": dob, "dl": dl, "sex": "MF"[i % 2]})
    return cast


def licence(r, person):
    """The same licence, keyed by whoever was on the desk."""
    dl = person["dl"]
    if dl is None:
        return "UNK"
    roll = r.random()
    if roll < 0.15:
        return dl.replace("-", "")
    if roll < 0.25:
        return dl.replace("-", " ")
    return dl


def main():
    ap = argparse.ArgumentParser(description="Simulate a Leon County SO CAD feed.")
    ap.add_argument("--rate", type=float, default=1.0,
                    help="Calls per second. Default: %(default)s")
    ap.add_argument("--count", type=int, default=0,
                    help="Stop after this many calls. 0 runs until killed.")
    ap.add_argument("--seed", type=int, default=None,
                    help="Makes a session reproducible. Omitted: a different shift every run.")
    ap.add_argument("--cast-seed", type=int, default=None,
                    help="Seeds only the cast, so the same people recur across restarts while every "
                         "call is still new. Omitted: --seed, or a new cast every run.")
    ap.add_argument("--start-incident", type=int, default=500,
                    help="First incident number. Default: %(default)s")
    ap.add_argument("--header", action="store_true",
                    help="Emit the header row first. Off by default: a live topic carries records, "
                         "not a file header, and the mapping declares its own columns anyway.")
    args = ap.parse_args()

    r = random.Random(args.seed)
    # The cast from its own seed when one is given. A stack that restarts must meet the same
    # people again -- a new cast every restart made the "who recurs" views about restarts, not
    # about the calls -- while the calls themselves keep coming from the session's own stream.
    cast_seed = args.cast_seed if args.cast_seed is not None else args.seed
    cast = build_cast(random.Random(cast_seed) if cast_seed is not None else r)
    # Four pairs who turn up together more than once. This is the recurrence the graph exists for.
    pairs = [(0, 5), (2, 11), (7, 14), (3, 18)]

    if args.header:
        print("INC_NUM,CALL_TYPE,RPT_DTTM,ADDR,BEAT,ROLE,NAME_FULL,DOB,SEX,DL_NUM", flush=True)

    inc = args.start_incident
    emitted = 0
    while args.count == 0 or emitted < args.count:
        inc += r.randint(1, 3)
        num = f"2026-{inc:06d}"
        call = r.choice(CALLS)
        # Reported now. The mapping declares America/New_York for this agency and the export
        # carries no offset, which is exactly the situation the declared zone exists for.
        when = datetime.now().strftime("%Y/%m/%d %H:%M")
        addr = f"{r.randint(12, 9870)} {r.choice(STREETS)}"
        beat = r.choice(BEATS)

        if r.random() < 0.22:
            a, b = r.choice(pairs)
            named = [(a, "VICT"), (b, "SUSP")]
        else:
            roles = ["VICT"] if call in ("BURG", "THEFT", "ASSLT") else ["RP"]
            if r.random() < 0.55:
                roles.append("SUSP")
            if r.random() < 0.30:
                roles.append("WITN")
            named = list(zip(r.sample(range(len(cast)), len(roles)), roles))

        for who, role in named:
            p = cast[who]
            print(f'{num},{call},{when},"{addr}",{beat},{role},"{p["name"]}",{p["dob"]},'
                  f'{p["sex"]},{licence(r, p)}', flush=True)

        emitted += 1
        if args.rate > 0:
            time.sleep(1.0 / args.rate)


if __name__ == "__main__":
    try:
        main()
    except (BrokenPipeError, KeyboardInterrupt):
        # The consumer went away, or someone pressed ctrl-c. Neither is an error worth a stack
        # trace across the demo output.
        sys.exit(0)
