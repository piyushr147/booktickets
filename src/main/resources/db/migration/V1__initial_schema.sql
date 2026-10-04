CREATE TABLE shows (
    id UUID PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    price_paise BIGINT NOT NULL CHECK (price_paise >= 0),
    per_user_limit INTEGER NOT NULL DEFAULT 4 CHECK (per_user_limit > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE reservations (
    id UUID PRIMARY KEY,
    show_id UUID NOT NULL REFERENCES shows(id),
    user_id VARCHAR(200) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('confirmed', 'cancelled')),
    amount_paise BIGINT NOT NULL CHECK (amount_paise >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    cancelled_at TIMESTAMPTZ
);
CREATE INDEX reservations_user_show_status_idx
    ON reservations(show_id, user_id, status);

CREATE TABLE seats (
    show_id UUID NOT NULL REFERENCES shows(id),
    label VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'available'
        CHECK (status IN ('available', 'held', 'confirmed')),
    user_id VARCHAR(200),
    reservation_id UUID REFERENCES reservations(id),
    PRIMARY KEY (show_id, label),
    CHECK (
        (status = 'available' AND user_id IS NULL AND reservation_id IS NULL)
        OR
        (status IN ('held', 'confirmed') AND user_id IS NOT NULL AND reservation_id IS NOT NULL)
    )
);
CREATE INDEX seats_reservation_idx ON seats(reservation_id) WHERE reservation_id IS NOT NULL;

CREATE TABLE reservation_seats (
    reservation_id UUID NOT NULL REFERENCES reservations(id),
    show_id UUID NOT NULL,
    seat_label VARCHAR(32) NOT NULL,
    PRIMARY KEY (reservation_id, seat_label),
    FOREIGN KEY (show_id, seat_label) REFERENCES seats(show_id, label)
);

CREATE TABLE user_quota (
    show_id UUID NOT NULL REFERENCES shows(id),
    user_id VARCHAR(200) NOT NULL,
    seat_count INTEGER NOT NULL DEFAULT 0 CHECK (seat_count >= 0),
    PRIMARY KEY (show_id, user_id)
);

CREATE TABLE idempotency_keys (
    user_id VARCHAR(200) NOT NULL,
    key VARCHAR(200) NOT NULL,
    show_id UUID NOT NULL REFERENCES shows(id),
    request_hash CHAR(64) NOT NULL,
    response_status INTEGER,
    response_body JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, key),
    CHECK ((response_status IS NULL) = (response_body IS NULL))
);
