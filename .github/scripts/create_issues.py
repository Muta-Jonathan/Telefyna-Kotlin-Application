#!/usr/bin/env python3
import sys
import json
import urllib.request
import re
import ssl

def get_existing_issues(token, repo, ctx):
    """Fetch existing issues (open and closed) to prevent duplicates and update tags."""
    url = f"https://api.github.com/repos/{repo}/issues?state=all&per_page=100"
    headers = {
        "Authorization": f"token {token}",
        "Accept": "application/vnd.github.v3+json",
    }
    req = urllib.request.Request(url, headers=headers)
    existing_issues = {}
    try:
        with urllib.request.urlopen(req, context=ctx) as response:
            if response.status == 200:
                issues = json.loads(response.read().decode('utf-8'))
                for issue in issues:
                    existing_issues[issue['title'].strip()] = issue['number']
    except Exception as e:
        print(f"⚠️ Could not fetch existing issues: {e}")
    return existing_issues

def update_issue_labels(issue_number, labels, token, repo, ctx):
    url = f"https://api.github.com/repos/{repo}/issues/{issue_number}"
    headers = {
        "Authorization": f"token {token}",
        "Accept": "application/vnd.github.v3+json",
        "Content-Type": "application/json"
    }
    data = {"labels": labels}
    req = urllib.request.Request(url, data=json.dumps(data).encode('utf-8'), headers=headers)
    req.get_method = lambda: 'PATCH'
    
    try:
        with urllib.request.urlopen(req, context=ctx) as response:
            if response.status == 200:
                print(f"   🏷️  Added labels {labels} to Issue #{issue_number}")
    except Exception as e:
        print(f"   ❌ Failed to add labels to Issue #{issue_number}: {str(e)}")

def create_issue(title, body, is_solved, labels, token, repo, ctx):
    url = f"https://api.github.com/repos/{repo}/issues"
    headers = {
        "Authorization": f"token {token}",
        "Accept": "application/vnd.github.v3+json",
        "Content-Type": "application/json"
    }
    data = {"title": title, "body": body, "labels": labels}
    req = urllib.request.Request(url, data=json.dumps(data).encode('utf-8'), headers=headers)
    
    try:
        with urllib.request.urlopen(req, context=ctx) as response:
            if response.status == 201:
                res_data = json.loads(response.read().decode('utf-8'))
                issue_number = res_data['number']
                print(f"✅ Created: '{title}' -> {res_data['html_url']}")
                print(f"   🏷️  Added labels {labels}")
                
                # If it's solved, close it immediately
                if is_solved:
                    close_url = f"https://api.github.com/repos/{repo}/issues/{issue_number}"
                    close_data = {"state": "closed"}
                    close_req = urllib.request.Request(close_url, data=json.dumps(close_data).encode('utf-8'), headers=headers)
                    close_req.get_method = lambda: 'PATCH'
                    with urllib.request.urlopen(close_req, context=ctx) as close_res:
                        if close_res.status == 200:
                            print(f"   🔒 Closed issue #{issue_number} (Marked as SOLVED)")
            else:
                print(f"❌ Failed to create '{title}': Status {response.status}")
    except Exception as e:
        print(f"❌ Failed to create '{title}': {str(e)}")

def main():
    if len(sys.argv) < 3:
        print("Usage: python3 create_issues.py <GITHUB_TOKEN> <REPO_OWNER/REPO_NAME>")
        sys.exit(1)

    token = sys.argv[1]
    repo = sys.argv[2]
    
    # Support running from repo root (GitHub Actions) or from the scripts folder (locally)
    import os
    if os.path.exists("README.md"):
        readme_path = "README.md"
    else:
        readme_path = "../../README.md"

    try:
        with open(readme_path, "r", encoding="utf-8") as f:
            lines = f.readlines()
    except FileNotFoundError:
        print(f"❌ Could not find {readme_path}")
        sys.exit(1)

    print(f"🔍 Scanning {readme_path} for To-Do items...")
    
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE

    print("📥 Fetching existing issues...")
    existing_issues = get_existing_issues(token, repo, ctx)

    regex = re.compile(r"^\s*-\s*\[([ x])\]\s+(.*)")

    issues_to_create = []
    
    for line in lines:
        match = regex.match(line)
        if match:
            is_solved = match.group(1).lower() == 'x'
            raw_text = match.group(2).strip()
            raw_text = raw_text.replace("**[SOLVED]**", "").replace("**[SOLVED]", "").replace("[SOLVED]**", "").strip()
            
            if ":" in raw_text:
                parts = raw_text.split(":", 1)
                title = parts[0].replace("**", "").strip()
                body = parts[1].strip()
            else:
                title = raw_text.replace("**", "").strip()
                body = "Extracted from Telefyna README.md Roadmap."
                
            labels = ["completed"] if is_solved else ["enhancement", "todo"]
            
            # Auto-categorize based on keywords in title
            title_lower = title.lower() + " " + body.lower()
            
            if any(k in title_lower for k in ["exoplayer", "stream", "hls", "rtmp", "srt", "buffer", "video", "playout", "player", "youtube"]):
                labels.append("streaming")
                labels.append("player")
                
            if any(k in title_lower for k in ["metrics", "cpu", "ram", "memory", "audit", "log", "report"]):
                labels.append("metrics")
                
            if any(k in title_lower for k in ["watchdog", "kill", "resilience", "crash", "stability", "fallback"]):
                labels.append("stability")
                
            if any(k in title_lower for k in ["playlist", "schedule", "midnight", "folder", "local", "bumpers"]):
                labels.append("scheduling")

            if title in existing_issues:
                issue_number = existing_issues[title]
                print(f"🔄 Issue already exists: '{title}' (Issue #{issue_number})")
                update_issue_labels(issue_number, labels, token, repo, ctx)
                continue

            issues_to_create.append((title, body, is_solved, labels))

    if not issues_to_create:
        print("✅ No new issues found to create.")
        sys.exit(0)

    print(f"🚀 Found {len(issues_to_create)} new issues to create. Pushing to GitHub API...")
    for title, body, is_solved, labels in issues_to_create:
        create_issue(title, body, is_solved, labels, token, repo, ctx)

if __name__ == "__main__":
    main()
