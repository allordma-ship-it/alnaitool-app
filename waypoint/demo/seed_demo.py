"""Seeds a Wanees demo server with Syrian drivers, rides and bookings via the public API."""
import json
import urllib.request
from datetime import datetime, timedelta, timezone

import os

API = os.environ.get("WANEES_API", "http://localhost:8080") + "/v1"
PSQL = os.environ.get("DEMO_PSQL_URL", "postgres://postgres@localhost:5433/wanees_demo")
DAMASCUS_TZ = timezone(timedelta(hours=3))

CITY = {
    "دمشق": (33.5138, 36.2765),
    "حلب": (36.2021, 37.1343),
    "حمص": (34.7324, 36.7137),
    "حماة": (35.1318, 36.7578),
    "اللاذقية": (35.5317, 35.7900),
    "طرطوس": (34.8890, 35.8866),
    "درعا": (32.6189, 36.1021),
}


def call(method, path, body=None, token=None):
    req = urllib.request.Request(API + path, method=method, data=json.dumps(body).encode() if body is not None else None)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        raise SystemExit(f"{method} {path} -> {e.code}: {e.read().decode()}")


def user(first, last, email):
    res = call("POST", "/auth/register", {"email": email, "password": "wanees2026", "first_name": first, "last_name": last, "phone": "+963 944 000 000"})
    return res["tokens"]["access_token"], res["user"]["id"]


def place(name):
    lat, lng = CITY[name]
    return {"name": name, "location": {"lat": lat, "lng": lng}}


def publish(token, frm, to, day_offset, hour, minute, seats, instant=True, car="", notes="", price_delta=0, prefs=None):
    o, d = place(frm), place(to)
    pv = call("POST", "/rides/preview", {"origin": o["location"], "destination": d["location"]}, token)
    day = datetime.now(DAMASCUS_TZ).date() + timedelta(days=day_offset)
    dep = datetime(day.year, day.month, day.day, hour, minute, tzinfo=DAMASCUS_TZ)
    p = {"instant_booking": instant, "allow_smoking": False, "allow_pets": False, "max_two_back": True}
    p.update(prefs or {})
    ride = call("POST", "/rides", {
        "origin": o, "destination": d, "departure_at": dep.isoformat(),
        "seats_total": seats, "price_per_seat_cents": pv["suggested_price_cents"] + price_delta,
        "preferences": p, "vehicle_description": car, "notes": notes,
    }, token)
    print(f"  ride {frm} -> {to} {dep:%a %H:%M}: {pv['distance_m']/1000:.0f} km, {ride['price_per_seat_cents']//100} SYP")
    return ride


def book(token, ride_id, frm, to, seats=1, message=""):
    return call("POST", f"/rides/{ride_id}/bookings", {"seats": seats, "pickup": place(frm), "dropoff": place(to), "message": message}, token)


def review(token, booking_id, rating, comment):
    call("POST", f"/bookings/{booking_id}/review", {"rating": rating, "comment": comment}, token)


print("users")
samer, _ = user("سامر", "الحلبي", "samer@wanees.demo")
rana, _ = user("رنا", "الخطيب", "rana@wanees.demo")
khaled, _ = user("خالد", "العلي", "khaled@wanees.demo")
layla, _ = user("ليلى", "الشامي", "layla@wanees.demo")
omar, _ = user("عمر", "الحمصي", "omar@wanees.demo")
ahmad, _ = user("أحمد", "النجار", "ahmad@wanees.demo")  # the demo user driven in the simulation

print("rides")
r1 = publish(samer, "دمشق", "حلب", 1, 8, 0, 3, car="كيا سيراتو بيضاء", notes="انطلاق من ساحة الأمويين. استراحة قصيرة في حمص.")
r2 = publish(khaled, "دمشق", "حلب", 1, 11, 30, 4, car="هيونداي أكسنت فضية", price_delta=-5000, prefs={"allow_smoking": True})
r3 = publish(rana, "دمشق", "اللاذقية", 1, 9, 30, 2, instant=False, car="تويوتا كورولا", notes="للنساء والعائلات فقط من فضلكم.")
r4 = publish(layla, "حمص", "طرطوس", 1, 14, 0, 3, car="شيفروليه أفيو")
r5 = publish(omar, "دمشق", "درعا", 2, 10, 0, 3, car="بيجو 405")
r6 = publish(samer, "دمشق", "حلب", 1, 17, 0, 2, car="كيا سيراتو بيضاء", price_delta=5000)

# Ahmad also drives: Aleppo -> Damascus the day after tomorrow, with a pending request.
ra = publish(ahmad, "حلب", "دمشق", 2, 7, 30, 3, instant=False, car="مازدا 3 رمادية", notes="أمرّ على حماة وحمص.")
book(layla, ra["id"], "حماة", "دمشق", 1, "مرحباً! هل يمكنني الصعود من دوار حماة؟")
book(omar, ra["id"], "حلب", "حمص", 2, "نحن شخصان مع حقيبتين.")

# Other passengers already on Samer's morning ride (so seats show as partly booked).
book(layla, r1["id"], "دمشق", "حمص", 1)

# Past trips with reviews give the drivers ratings.
print("ratings (via completed past trips)")
import subprocess
for driver_tok, frm, to, rating, comment in [
    (samer, "دمشق", "حمص", 5, "سائق محترم وملتزم بالمواعيد"),
    (khaled, "دمشق", "حماة", 4, "رحلة مريحة"),
    (rana, "دمشق", "طرطوس", 5, "رائعة جداً، أنصح بها"),
]:
    past = publish(driver_tok, frm, to, 1, 6, 0, 2)
    b = book(ahmad if driver_tok is not samer else omar, past["id"], frm, to, 1)
    # Moves this ride into the past so it can be completed and reviewed.
    subprocess.run(["psql", PSQL, "-qc",
                    f"UPDATE rides SET departure_at = NOW() - INTERVAL '3 days', arrival_at = NOW() - INTERVAL '3 days' + INTERVAL '2 hours' WHERE id = '{past['id']}'"], check=True)
    call("POST", f"/rides/{past['id']}/complete", None, driver_tok)
    review(ahmad if driver_tok is not samer else omar, b["id"], rating, comment)

print("done — demo login: ahmad@wanees.demo / wanees2026")
