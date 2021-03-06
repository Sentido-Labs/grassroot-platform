package za.org.grassroot.services.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.org.grassroot.core.domain.User;
import za.org.grassroot.core.domain.VerificationTokenCode;
import za.org.grassroot.core.enums.VerificationCodeType;
import za.org.grassroot.core.repository.UserRepository;
import za.org.grassroot.core.repository.VerificationTokenCodeRepository;
import za.org.grassroot.core.util.InvalidPhoneNumberException;
import za.org.grassroot.core.util.PhoneNumberUtil;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Random;

/**
 * @author Lesetse Kimwaga
 */
@Service
public class PasswordTokenManager implements PasswordTokenService {

    public static final int TOKEN_LIFE_SPAN_MINUTES = 5;
    public static final int TOKEN_LIFE_SPAN_DAYS = 10;

    private static final Logger log = LoggerFactory.getLogger(PasswordTokenManager.class);

    @Autowired
    private VerificationTokenCodeRepository verificationTokenCodeRepository;

    @Autowired
    private UserRepository userRepository;

    @Override
    @Transactional
    public VerificationTokenCode generateShortLivedOTP(String phoneNumber) {
        Objects.requireNonNull(phoneNumber);
        if (!PhoneNumberUtil.testInputNumber(phoneNumber)) {
            throw new InvalidPhoneNumberException("Error! Phone number must be passed in valid format");
        }

        final String msisdn = PhoneNumberUtil.convertPhoneNumber(phoneNumber); // should only ever be passed msisdn, but extra robustness relatively cost-less

        VerificationTokenCode token = verificationTokenCodeRepository.findByUsernameAndType(msisdn, VerificationCodeType.SHORT_OTP);
        final String code = String.valueOf(100000 + new SecureRandom().nextInt(999999));

        if (token == null) {
            // no OTP exists, so generate a new one and send it
            token = new VerificationTokenCode(msisdn, code, VerificationCodeType.SHORT_OTP);
            token.setExpiryDateTime(Instant.now().plus(TOKEN_LIFE_SPAN_MINUTES, ChronoUnit.MINUTES));
        } else if (Instant.now().isAfter(token.getExpiryDateTime())) {
            // an OTP exists but it is stale
            log.info("found an OTP, but it's stale, time now = {}, expiry time = {}", Instant.now(), token.getExpiryDateTime().toString());
            token.setCode(code);
            token.updateCreatedDateTime();
            token.incrementTokenAttempts();
            token.setExpiryDateTime(Instant.now().plus(TOKEN_LIFE_SPAN_MINUTES, ChronoUnit.MINUTES));
        } else {
            // an OTP exists and is not stale, so increment token attemps but leave the rest unchanged
            token.incrementTokenAttempts();
        }

        verificationTokenCodeRepository.save(token);
        return token;
    }

    @Override
    @Transactional
    public VerificationTokenCode generateLongLivedAuthCode(String userUid) {
        Objects.requireNonNull(userUid);

        User user = userRepository.findOneByUid(userUid);
        VerificationTokenCode token = verificationTokenCodeRepository.findByUsernameAndType(user.getUsername(),
                VerificationCodeType.LONG_AUTH);

        Random random = new SecureRandom();
        final String code = String.valueOf(new BigInteger(130, random));

        if (token == null) {
            token = new VerificationTokenCode(user.getPhoneNumber(), code, VerificationCodeType.LONG_AUTH);
            token.setExpiryDateTime(Instant.now().plus(TOKEN_LIFE_SPAN_DAYS, ChronoUnit.DAYS));
        } else if (Instant.now().isAfter(token.getExpiryDateTime())) {
            token.setCode(code);
            token.setExpiryDateTime(Instant.now().plus(TOKEN_LIFE_SPAN_DAYS, ChronoUnit.DAYS));
        } else {
            token.incrementTokenAttempts();
        }

        verificationTokenCodeRepository.save(token);
        return token;
    }

    @Override
    @Transactional(readOnly = true)
    public VerificationTokenCode fetchLongLivedAuthCode(String phoneNumber) {
       return verificationTokenCodeRepository.findByUsernameAndType(phoneNumber, VerificationCodeType.LONG_AUTH);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isShortLivedOtpValid(String phoneNumber, String code) {
        if (phoneNumber == null || code == null) {
            return false;
        }

        // need to use directly as phone number, not attempt to get user first, else fails on registration
        VerificationTokenCode token = verificationTokenCodeRepository.findByUsernameAndType(phoneNumber, VerificationCodeType.SHORT_OTP);

        return token != null && code.equals(token.getCode()) && Instant.now().isBefore(token.getExpiryDateTime());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isLongLiveAuthValid(String phoneNumber, String code) {
        if (phoneNumber == null || code == null) {
            return false;
        }

        User user = userRepository.findByPhoneNumber(PhoneNumberUtil.convertPhoneNumber(phoneNumber));
        if (user == null) {
            return false;
        }

        VerificationTokenCode token = verificationTokenCodeRepository.findByUsernameAndType(user.getUsername(), VerificationCodeType.LONG_AUTH);
        return token != null && code.equals(token.getCode()) && Instant.now().isBefore(token.getExpiryDateTime());
    }

    @Override
    public boolean isExpired(VerificationTokenCode tokenCode){
        return Instant.now().isBefore(tokenCode.getExpiryDateTime());
    }

    @Override
    @Transactional
    public void expireVerificationCode(String userUid, VerificationCodeType type) {
        Objects.requireNonNull(userUid);
        Objects.requireNonNull(type);

        User user = userRepository.findOneByUid(userUid);
        VerificationTokenCode token = verificationTokenCodeRepository.findByUsernameAndType(user.getUsername(), type);

        if (token != null) {
            token.setExpiryDateTime(Instant.now());
        }
    }
}