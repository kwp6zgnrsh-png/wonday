package com.wonday.insurance.member;

import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
class MemberService implements UserDetailsService {

	private static final int BCRYPT_MAX_PASSWORD_BYTES = 72;
	private static final String LOGIN_ID_UNIQUE_KEY = "uq_member_login";

	private final MemberRepository members;
	private final PasswordEncoder passwordEncoder;

	@Transactional
	public Member register(String loginId, String name, String password, NationalityType nationalityType) {
		String normalizedLoginId = loginId.trim();
		if (normalizedLoginId.isBlank() || loginId.codePoints().anyMatch(Character::isISOControl)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid login ID");
		}
		if (password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_PASSWORD_BYTES) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at most 72 UTF-8 bytes");
		}

		try {
			return members.saveAndFlush(new Member(normalizedLoginId, passwordEncoder.encode(password),
				name.strip(), MemberRole.WORKER, nationalityType));
		} catch (DataIntegrityViolationException exception) {
			if (exception.getCause() instanceof ConstraintViolationException violation) {
				String key = violation.getConstraintName();
				if (LOGIN_ID_UNIQUE_KEY.equals(key) || ("member." + LOGIN_ID_UNIQUE_KEY).equals(key)) {
					throw new ResponseStatusException(HttpStatus.CONFLICT, "Login ID is already in use", exception);
				}
			}
			throw exception;
		}
	}

	@Override
	@Transactional(readOnly = true)
	public UserDetails loadUserByUsername(String loginId) {
		return members.findByLoginId(loginId)
			.map(MemberPrincipal::new)
			.orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
	}

	@Transactional(readOnly = true)
	public Member current(long memberId) {
		return members.findById(memberId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Member does not exist"));
	}
}
