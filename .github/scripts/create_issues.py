#!/usr/bin/env python3
import sys
import json
import urllib.request
import re
import ssl

def create_issue(title, body, token, repo):
    url = f"https://api.github.com/repos/{repo}/issues"
    headers = {
        "Authorization": f"token {token}",
        "Accept": "application/vnd.github.v3+json",
        "Content-Type": "application/json"
    }
    data = {"title": title, "body": body}
    req = urllib.request.Request(url, data=json.dumps(data).encode('utf-8'), headers=headers)
    
    # Bypass macOS Python SSL certificate issues
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE

    try:
        with urllib.request.urlopen(req, context=ctx) as response:
            if response.status == 201:
                res_data = json.loads(response.read().decode('utf-8'))
                print(f"✅ Created: '{title}' -> {res_data['html_url']}")
            else:
                print(f"❌ Failed to create '{title}': Status {response.status}")
    except Exception as e:
        print(f"❌ Failed to create '{title}': {str(e)}")

def main():
    if len(sys.argv) < 3:
        print("Usage: python3 create_issues.py <GITHUB_TOKEN> <REPO_OWNER/REPO_NAME>")
        print("Example: python3 create_issues.py ghp_yourToken123 AvventoMedia/Telefyna-Kotlin-Application")
        sys.exit(1)

    token = sys.argv[1]
    repo = sys.argv[2]
    readme_path = "../../README.md"

    try:
        with open(readme_path, "r", encoding="utf-8") as f:
            lines = f.readlines()
    except FileNotFoundError:
        print(f"❌ Could not find {readme_path}")
        sys.exit(1)

    print(f"🔍 Scanning {readme_path} for unchecked To-Do items...")
    
    # Regex to find unchecked items: e.g. "- [ ] **Feature:** Description" or "- [ ] Description"
    regex = re.compile(r"^\s*-\s*\[\s\]\s+(.*)")

    issues_to_create = []
    
    for line in lines:
        match = regex.match(line)
        if match:
            raw_text = match.group(1).strip()
            
            # Split title and description if there's a colon
            if ":" in raw_text:
                parts = raw_text.split(":", 1)
                title = parts[0].replace("**", "").strip()
                body = parts[1].strip()
            else:
                title = raw_text
                body = "Extracted from Telefyna README.md Roadmap."
                
            issues_to_create.append((title, body))

    if not issues_to_create:
        print("✅ No unchecked [ ] items found in README.md.")
        sys.exit(0)

    print(f"🚀 Found {len(issues_to_create)} issues to create. Pushing to GitHub API...")
    
    for title, body in issues_to_create:
        create_issue(title, body, token, repo)

if __name__ == "__main__":
    main()
