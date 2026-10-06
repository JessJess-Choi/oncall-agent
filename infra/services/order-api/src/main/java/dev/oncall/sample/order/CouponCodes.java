package dev.oncall.sample.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/** 쿠폰 코드 정규화. 1.2.0에서 호출부의 null 검사를 없애고 이 메서드에 맡겼는데, 여기에는 null 처리가 없다. */
final class CouponCodes {

    private static final Logger log = LoggerFactory.getLogger(CouponCodes.class);

    private CouponCodes() {
    }

    static String normalize(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }

    static void warnUnknown(String code) {
        log.warn("Unknown coupon code {}, no discount applied", code);
    }
}
