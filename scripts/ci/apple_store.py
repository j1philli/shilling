#!/usr/bin/env python3
"""Upload tested IPAs and promote exact Apple build IDs; never pick 'latest'."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

from ios_provenance import load_candidate

API = "https://api.appstoreconnect.apple.com"
RELEASED_STATES = {"PROCESSING_FOR_APP_STORE", "PENDING_APPLE_RELEASE", "READY_FOR_SALE", "READY_FOR_DISTRIBUTION"}


class AppleAPI:
    def __init__(self):
        self.app_id = os.environ["ASC_APP_ID"]

    def request(self, method, path, body=None, **params):
        import jwt
        # Only release/review jobs receive this password parameter. Xcode jobs
        # use a runner-local key file, so private key contents never reach Xcode.
        key = os.environ.get("ASC_API_PRIVATE_KEY")
        if not key:
            key = Path(os.environ["ASC_KEY_PATH"]).read_text()
        now = int(time.time())
        token = jwt.encode({"iss": os.environ["ASC_ISSUER_ID"], "iat": now,
                            "exp": now + 600, "aud": "appstoreconnect-v1"}, key,
                           algorithm="ES256", headers={"kid": os.environ["ASC_KEY_ID"], "typ": "JWT"})
        url = path if path.startswith(API + "/") else API + path
        if not url.startswith(API + "/v1/"):
            raise ValueError("Unexpected Apple API URL")
        if params:
            url += "?" + urllib.parse.urlencode(params)
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(url, data=data, method=method, headers={
            "Authorization": "Bearer " + token, "Content-Type": "application/json"})
        for attempt in range(4):
            try:
                with urllib.request.urlopen(request, timeout=60) as response:
                    raw = response.read()
                    return json.loads(raw) if raw else {}
            except urllib.error.HTTPError as error:
                # GETs are safe to retry. A failed mutation may have succeeded;
                # let the next job run reconcile remote state before retrying it.
                if method == "GET" and error.code in (429, 500, 502, 503, 504) and attempt < 3:
                    time.sleep(5 * (attempt + 1))
                    continue
                detail = error.read().decode()[:3000]
                raise RuntimeError(f"Apple {method} {path}: HTTP {error.code}: {detail}") from None

    def list(self, path, **params):
        result = self.request("GET", path, **params)
        rows = result["data"]
        while result.get("links", {}).get("next"):
            result = self.request("GET", result["links"]["next"])
            rows.extend(result["data"])
        return rows


def resource(kind, id=None, attributes=None, relationships=None):
    data = {"type": kind}
    if id is not None:
        data["id"] = id
    if attributes is not None:
        data["attributes"] = attributes
    if relationships is not None:
        data["relationships"] = {k: {"data": v} for k, v in relationships.items()}
    return {"data": data}


def link(kind, id):
    return {"type": kind, "id": id}


def find_build(api, candidate):
    rows = api.list("/v1/builds", **{"filter[app]": api.app_id, "filter[version]": candidate["build_number"]})
    matches = []
    for row in rows:
        prerelease = api.request("GET", f"/v1/builds/{row['id']}/preReleaseVersion")["data"]["attributes"]
        if prerelease["version"] == candidate["version"] and prerelease["platform"] == "IOS":
            matches.append(row)
    if len(matches) > 1:
        raise ValueError("Ambiguous Apple build; refusing to select a candidate")
    return matches[0] if matches else None


def valid_build(build):
    if not build:
        raise ValueError("This exact iOS build is not uploaded yet; wait for iOS — TestFlight")
    attrs = build["attributes"]
    if attrs["processingState"] != "VALID" or attrs.get("expired"):
        raise ValueError("Apple build is invalid, expired, or still processing")
    if attrs.get("buildAudienceType") != "APP_STORE_ELIGIBLE":
        raise ValueError("Build is not eligible for App Store promotion (Internal Only upload)")


def wait_for_build(api, candidate, timeout=1800):
    deadline = time.monotonic() + timeout
    while True:
        build = find_build(api, candidate)
        if build:
            state = build["attributes"]["processingState"]
            if state in ("FAILED", "INVALID"):
                raise ValueError(f"Apple rejected build processing: {state}")
            if state == "VALID":
                valid_build(build)
                return build
        if time.monotonic() >= deadline:
            raise ValueError("Apple processing timed out; rerun this job to check the same upload")
        print("Waiting for Apple to process the selected build…", flush=True)
        time.sleep(30)


def apply_saved_compliance(api, build):
    # Reuse the user's completed Apple questionnaire, never infer an exemption
    # from the transport implementation. Change this reference after a crypto
    # or territory policy change, and complete the questionnaire again.
    reference_id = os.environ["ASC_ENCRYPTION_REFERENCE_BUILD_ID"]
    reference = api.request("GET", f"/v1/builds/{reference_id}", include="appEncryptionDeclaration,app")["data"]
    if reference["relationships"]["app"]["data"]["id"] != api.app_id:
        raise ValueError("Encryption reference belongs to a different app")
    exempt = reference["attributes"].get("usesNonExemptEncryption")
    declaration = reference["relationships"]["appEncryptionDeclaration"]["data"]
    if exempt is None or (exempt and not declaration):
        raise ValueError("Reference build has no completed encryption answer")
    relationships = {"appEncryptionDeclaration": declaration} if declaration else None
    api.request("PATCH", f"/v1/builds/{build['id']}", resource("builds", build["id"],
                {"usesNonExemptEncryption": exempt}, relationships))


def upload(api, candidate, directory, output):
    Path(output).unlink(missing_ok=True)
    if os.environ.get("BUILD_VCS_BRANCH") != "main":
        raise ValueError("TestFlight uploads must run on main")
    # Validate the selected internal group before uploading anything.
    group_id = os.environ["ASC_INTERNAL_GROUP_ID"]
    group = api.request("GET", f"/v1/betaGroups/{group_id}", include="app")["data"]
    if not group["attributes"]["isInternalGroup"] or group["relationships"]["app"]["data"]["id"] != api.app_id:
        raise ValueError("TestFlight group must be an internal group for this app")
    build = find_build(api, candidate)
    if not build:
        subprocess.run(["xcrun", "altool", "--upload-package", str(Path(directory) / candidate["ipa"]),
                        "--api-key", os.environ["ASC_KEY_ID"], "--api-issuer", os.environ["ASC_ISSUER_ID"],
                        "--p8-file-path", os.environ["ASC_KEY_PATH"], "--wait", "--output-format", "json"], check=True)
    build = wait_for_build(api, candidate)
    apply_saved_compliance(api, build)
    # Groups with automatic access already include this build. Avoid a duplicate
    # association and never invite testers or create a public invitation link.
    if not group["attributes"].get("hasAccessToAllBuilds"):
        members = api.list(f"/v1/betaGroups/{group_id}/relationships/builds")
        if not any(row["id"] == build["id"] for row in members):
            api.request("POST", f"/v1/betaGroups/{group_id}/relationships/builds", {"data": [link("builds", build["id"])]})
    receipt = dict(candidate, apple_build_id=build["id"], app_id=api.app_id, internal_group_id=group_id)
    Path(output).parent.mkdir(parents=True, exist_ok=True)
    Path(output).write_text(json.dumps(receipt, indent=2) + "\n")
    print(f"TestFlight: {candidate['version']} ({candidate['build_number']}) — {candidate['commit']}")
    return receipt


def store_version(api, version):
    rows = api.list(f"/v1/apps/{api.app_id}/appStoreVersions", **{"filter[platform]": "IOS", "filter[versionString]": version})
    if len(rows) > 1:
        raise ValueError("Ambiguous App Store version")
    return rows[0] if rows else None


def state_of(version):
    return version["attributes"].get("appVersionState") or version["attributes"]["appStoreState"]


def approval(api, candidate):
    build = find_build(api, candidate)
    valid_build(build)
    version = store_version(api, candidate["version"])
    if not version:
        raise ValueError("No App Store version for this candidate; run iOS — Submit for Review first")
    attached = api.request("GET", f"/v1/appStoreVersions/{version['id']}/relationships/build")["data"]
    if not attached or attached["id"] != build["id"]:
        raise ValueError("Approved App Store build does not match this chain's tested IPA; promote the tested TeamCity chain")
    state = state_of(version)
    if state not in RELEASED_STATES and state != "PENDING_DEVELOPER_RELEASE":
        raise ValueError(f"iOS approval pending ({state}); nothing can be published until Apple approves this exact build")
    if version["attributes"]["releaseType"] != "MANUAL":
        raise ValueError("App Store version must use manual release")
    return dict(candidate, apple_build_id=build["id"], app_store_version_id=version["id"],
                app_id=api.app_id, state=state)


def promote(api, candidate, approved, output):
    # Recheck immediately before the irreversible request. The preflight runs
    # before any other target publishes, but Apple's state can change meanwhile.
    current = approval(api, candidate)
    for field in ("apple_build_id", "app_store_version_id", "app_id", "sha256", "commit"):
        if current[field] != approved[field]:
            raise ValueError("Apple release candidate changed after preflight")
    if current["state"] == "PENDING_DEVELOPER_RELEASE":
        api.request("POST", "/v1/appStoreVersionReleaseRequests", resource("appStoreVersionReleaseRequests",
                    relationships={"appStoreVersion": link("appStoreVersions", current["app_store_version_id"])}))
        current["state"] = "RELEASE_REQUESTED"
    Path(output).write_text(json.dumps(current, indent=2) + "\n")
    return current


def submit_review(api, candidate):
    if os.environ.get("BUILD_VCS_BRANCH") != "main":
        raise ValueError("App Store review must use a tested main build")
    build = find_build(api, candidate)
    valid_build(build)
    version = store_version(api, candidate["version"])
    if not version:
        # Apple creates the first draft as 1.0. Reuse that empty draft instead
        # of trying to create a second unreleased version.
        drafts = [v for v in api.list(f"/v1/apps/{api.app_id}/appStoreVersions", **{"filter[platform]": "IOS"})
                  if state_of(v) == "PREPARE_FOR_SUBMISSION"]
        if len(drafts) > 1:
            raise ValueError("Multiple App Store drafts; select the intended version in App Store Connect")
        if drafts:
            version = drafts[0]
            attached = api.request("GET", f"/v1/appStoreVersions/{version['id']}/relationships/build")["data"]
            if attached:
                raise ValueError("A different version already has a build selected; resolve that draft in App Store Connect")
        else:
            version = api.request("POST", "/v1/appStoreVersions", resource("appStoreVersions", attributes={
                "platform": "IOS", "versionString": candidate["version"], "releaseType": "MANUAL"},
                relationships={"app": link("apps", api.app_id)}))["data"]
    state = state_of(version)
    if state not in {"PREPARE_FOR_SUBMISSION", "DEVELOPER_REJECTED", "REJECTED", "METADATA_REJECTED", "READY_FOR_REVIEW"}:
        attached = api.request("GET", f"/v1/appStoreVersions/{version['id']}/relationships/build")["data"]
        if attached and attached["id"] == build["id"] and version["attributes"]["releaseType"] == "MANUAL":
            print(f"Selected candidate already submitted: {state}")
            return
        raise ValueError(f"App Store version cannot be changed in state {state}")
    api.request("PATCH", f"/v1/appStoreVersions/{version['id']}", resource("appStoreVersions", version["id"],
        {"versionString": candidate["version"], "releaseType": "MANUAL"}, {"build": link("builds", build["id"])}))
    submissions = api.list("/v1/reviewSubmissions", **{"filter[app]": api.app_id, "filter[platform]": "IOS", "filter[state]": "READY_FOR_REVIEW"})
    if len(submissions) > 1:
        raise ValueError("Multiple draft review submissions; resolve in App Store Connect")
    submission = submissions[0] if submissions else api.request("POST", "/v1/reviewSubmissions",
        resource("reviewSubmissions", attributes={"platform": "IOS"}, relationships={"app": link("apps", api.app_id)}))["data"]
    items = api.list(f"/v1/reviewSubmissions/{submission['id']}/items", include="appStoreVersion")
    if any(i.get("relationships", {}).get("appStoreVersion", {}).get("data") != link("appStoreVersions", version["id"]) for i in items):
        raise ValueError("Draft submission includes other items; submit it manually in App Store Connect")
    if not items:
        api.request("POST", "/v1/reviewSubmissionItems", resource("reviewSubmissionItems", relationships={
            "reviewSubmission": link("reviewSubmissions", submission["id"]),
            "appStoreVersion": link("appStoreVersions", version["id"])}))
    api.request("PATCH", f"/v1/reviewSubmissions/{submission['id']}", resource("reviewSubmissions", submission["id"], {"submitted": True}))
    print(f"Submitted {candidate['version']} ({candidate['build_number']}); Apple approval will wait for our release button.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("upload", "check", "submit-review"))
    parser.add_argument("--directory", default="release-input/clients/ios")
    parser.add_argument("--output", default="apple-output/testflight.json")
    args = parser.parse_args()
    version = Path("VERSION").read_text().strip()
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    candidate = load_candidate(args.directory, version, commit)
    api = AppleAPI()
    if args.action == "upload":
        upload(api, candidate, args.directory, args.output)
    elif args.action == "check":
        print(json.dumps(approval(api, candidate), indent=2))
    else:
        submit_review(api, candidate)


if __name__ == "__main__":
    try:
        main()
    except (KeyError, ValueError, RuntimeError, subprocess.CalledProcessError, urllib.error.URLError) as error:
        print(f"Apple workflow failed: {error}", file=sys.stderr)
        sys.exit(1)
