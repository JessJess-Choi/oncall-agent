-- 첫 기동 때 한 번만 실행된다 (볼륨이 비어 있을 때). 다시 만들려면 make down-clean 후 make up.
-- 시드를 고정해 데이터가 매번 같다.
SELECT setseed(0.42);

CREATE TABLE products (
    id    integer PRIMARY KEY,
    name  text    NOT NULL,
    price bigint  NOT NULL
);

INSERT INTO products (id, name, price)
SELECT i, 'product-' || i, (1000 + floor(random() * 99) * 500)::bigint
FROM generate_series(1, 100) AS i;

CREATE TABLE orders (
    id               bigserial   PRIMARY KEY,
    customer_id      bigint      NOT NULL,
    product_id       integer     NOT NULL REFERENCES products (id),
    quantity         integer     NOT NULL,
    amount           bigint      NOT NULL,
    status           text        NOT NULL,
    coupon_code      text,
    shipping_address text        NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now()
);

-- 400만 건. 인덱스가 없으면 고객별 조회가 테이블 전체를 읽는다 (slow_query 시나리오).
INSERT INTO orders (customer_id, product_id, quantity, amount, status, coupon_code, shipping_address, created_at)
SELECT 1 + floor(random() * 100000)::bigint,
       1 + floor(random() * 100)::int,
       1 + floor(random() * 3)::int,
       1000 + floor(random() * 100000)::bigint,
       'PAID',
       NULL,
       'Seoul, Gangnam-gu, Teheran-ro ' || i || ', ' || md5(i::text) || md5((i * 7)::text),
       now() - random() * interval '365 days'
FROM generate_series(1, 4000000) AS i;

CREATE INDEX idx_orders_customer_created ON orders (customer_id, created_at DESC);

ANALYZE products;
ANALYZE orders;
