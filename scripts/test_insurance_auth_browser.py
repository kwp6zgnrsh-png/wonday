"""Check the insurance auth proxy from both Vite origins with a real browser."""

import os
from uuid import uuid4

from playwright.sync_api import sync_playwright


def check_auth(page, origin):
    page.goto(origin)
    # Run fetch in the page so browser cookie and same-origin rules apply.
    result = page.evaluate(
        """async (loginId) => {
            const base = '/api/insurance/auth';
            const password = 'browser-test-password';
            const csrf = async () => {
                const response = await fetch(base + '/csrf');
                if (!response.ok) throw new Error('CSRF status: ' + response.status);
                const { headerName, token } = await response.json();
                return { [headerName]: token };
            };

            const signup = await fetch(base + '/signup', {
                method: 'POST',
                headers: { ...await csrf(), 'Content-Type': 'application/json' },
                body: JSON.stringify({ loginId, name: 'Browser Test', password }),
            });
            if (signup.status !== 201) throw new Error('Signup status: ' + signup.status);
            const member = await signup.json();
            const login = await fetch(base + '/login', {
                method: 'POST',
                headers: await csrf(),
                body: new URLSearchParams({ loginId, password }),
            });
            if (login.status !== 204) throw new Error('Login status: ' + login.status);
            const response = await fetch(base + '/me');
            if (!response.ok) throw new Error('Me status: ' + response.status);
            return { member, me: await response.json() };
        }""",
        "insurance-browser-" + uuid4().hex,
    )
    assert result["member"] == result["me"], result
    assert result["me"]["role"] == "WORKER", result
    print(f"PASS {origin}: CSRF, signup, form login, and session account lookup")


if __name__ == "__main__":
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(channel=os.getenv("PLAYWRIGHT_BROWSER_CHANNEL"))
        try:
            for origin in ("http://localhost:5173", "http://localhost:5174"):
                page = browser.new_page()
                try:
                    check_auth(page, origin)
                finally:
                    page.close()
        finally:
            browser.close()
