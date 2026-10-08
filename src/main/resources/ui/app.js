(() => {
  const state = {
    token: null,
    userId: null,
    role: null,
    project: null,
    version: null,
    pending: [],
  };

  const els = {
    loginView: document.getElementById("login-view"),
    workspaceView: document.getElementById("workspace-view"),
    loginForm: document.getElementById("login-form"),
    loginError: document.getElementById("login-error"),
    session: document.getElementById("session"),
    projectList: document.getElementById("project-list"),
    emptyState: document.getElementById("empty-state"),
    projectDetail: document.getElementById("project-detail"),
    projectTitle: document.getElementById("project-title"),
    versionList: document.getElementById("version-list"),
    versionLabel: document.getElementById("version-label"),
    fileList: document.getElementById("file-list"),
    adminGrant: document.getElementById("admin-grant"),
    adminEnroll: document.getElementById("admin-enroll"),
    adminUsers: document.getElementById("admin-users"),
    adminUpload: document.getElementById("admin-upload"),
    grantForm: document.getElementById("grant-form"),
    enrollForm: document.getElementById("enroll-form"),
    userList: document.getElementById("user-list"),
    uploadForm: document.getElementById("upload-form"),
    dropZone: document.getElementById("drop-zone"),
    pickFiles: document.getElementById("pick-files"),
    pickFolder: document.getElementById("pick-folder"),
    uploadFile: document.getElementById("upload-file"),
    uploadFolder: document.getElementById("upload-folder"),
    pendingFiles: document.getElementById("pending-files"),
    accountBox: document.getElementById("account-box"),
    passwordForm: document.getElementById("password-form"),
    signOut: document.getElementById("sign-out"),
    toast: document.getElementById("toast"),
    railCards: [...document.querySelectorAll('[data-accordion="rail"]')],
  };

  function isAdmin() {
    return state.role === "Admin";
  }

  function isSignedIn() {
    return Boolean(state.userId);
  }

  function clearSession() {
    state.token = null;
    state.userId = null;
    state.role = null;
    state.project = null;
    state.version = null;
  }

  function showToast(message) {
    els.toast.textContent = message;
    els.toast.hidden = false;
    window.clearTimeout(showToast.timer);
    showToast.timer = window.setTimeout(() => {
      els.toast.hidden = true;
    }, 2500);
  }

  function fileUrl(project, version, fileName) {
    const nested = fileName
      .split("/")
      .map((segment) => encodeURIComponent(segment))
      .join("/");
    return `/projects/${encodeURIComponent(project)}/versions/${encodeURIComponent(version)}/files/${nested}`;
  }

  async function api(path, options = {}) {
    const headers = new Headers(options.headers || {});
    if (state.token) {
      headers.set("Authorization", `Bearer ${state.token}`);
    }
    if (options.json !== undefined) {
      headers.set("Content-Type", "application/json");
    }
    const response = await fetch(path, {
      ...options,
      credentials: "same-origin",
      headers,
      body: options.json !== undefined ? JSON.stringify(options.json) : options.body,
    });
    if (!response.ok) {
      if (response.status === 401 && path !== "/login") {
        clearSession();
        clearPending();
        renderSession();
      }
      const text = await response.text();
      throw new Error(text || `${response.status} ${response.statusText}`);
    }
    if (response.status === 204) {
      return null;
    }
    const type = response.headers.get("content-type") || "";
    if (type.includes("application/json")) {
      return response.json();
    }
    return response;
  }

  function closeRailCards() {
    for (const card of els.railCards) {
      card.open = false;
    }
  }

  function renderSession() {
    const signedIn = isSignedIn();
    els.loginView.hidden = signedIn;
    els.workspaceView.hidden = !signedIn;
    els.session.hidden = !signedIn;
    if (signedIn) {
      els.session.textContent = `${state.userId} · ${state.role}`;
    }
    els.adminEnroll.hidden = !isAdmin();
    els.adminGrant.hidden = !isAdmin();
    els.adminUsers.hidden = !isAdmin();
    els.adminUpload.hidden = !isAdmin();
    els.accountBox.hidden = !signedIn;
    if (!signedIn) {
      closeRailCards();
    }
  }

  function renderUsers(users) {
    els.userList.replaceChildren();
    for (const user of users) {
      const item = document.createElement("li");
      item.textContent = `${user.userId} · ${user.role}`;
      els.userList.append(item);
    }
  }

  async function refreshUsers() {
    if (!isAdmin()) {
      return;
    }
    const data = await api("/users");
    renderUsers(data.users);
  }

  function renderPending() {
    els.pendingFiles.replaceChildren();
    for (const item of state.pending) {
      const li = document.createElement("li");
      li.textContent = item.relativePath;
      els.pendingFiles.append(li);
    }
  }

  function queueFiles(entries) {
    const byPath = new Map(state.pending.map((item) => [item.relativePath, item]));
    for (const entry of entries) {
      byPath.set(entry.relativePath, entry);
    }
    state.pending = [...byPath.values()].sort((a, b) =>
      a.relativePath.localeCompare(b.relativePath),
    );
    renderPending();
  }

  function clearPending() {
    state.pending = [];
    renderPending();
  }

  function readFileEntry(fileEntry, relativePath) {
    return new Promise((resolve, reject) => {
      fileEntry.file(
        (file) => resolve({ file, relativePath }),
        reject,
      );
    });
  }

  function readDirectoryEntry(directoryEntry, prefix) {
    return new Promise((resolve, reject) => {
      const reader = directoryEntry.createReader();
      const collected = [];

      const readBatch = () => {
        reader.readEntries(async (entries) => {
          if (entries.length === 0) {
            resolve(collected.flat());
            return;
          }
          try {
            const nested = await Promise.all(
              entries.map((entry) => collectEntry(entry, prefix)),
            );
            collected.push(...nested);
            readBatch();
          } catch (error) {
            reject(error);
          }
        }, reject);
      };

      readBatch();
    });
  }

  async function collectEntry(entry, prefix = "") {
    if (!entry) {
      return [];
    }
    const relativePath = prefix ? `${prefix}/${entry.name}` : entry.name;
    if (entry.isFile) {
      const item = await readFileEntry(entry, relativePath);
      return [item];
    }
    if (entry.isDirectory) {
      return readDirectoryEntry(entry, relativePath);
    }
    return [];
  }

  async function collectFromDataTransfer(dataTransfer) {
    const items = [...(dataTransfer.items || [])];
    if (items.length > 0 && items[0].webkitGetAsEntry) {
      const nested = await Promise.all(
        items.map(async (item) => {
          const entry = item.webkitGetAsEntry();
          return collectEntry(entry);
        }),
      );
      return nested.flat();
    }
    return [...(dataTransfer.files || [])].map((file) => ({
      file,
      relativePath: file.name,
    }));
  }

  function collectFromFileList(fileList, asFolder) {
    return [...fileList].map((file) => {
      const relativePath = asFolder && file.webkitRelativePath
        ? file.webkitRelativePath
        : file.name;
      return { file, relativePath };
    });
  }

  function renderProjects(projects) {
    els.projectList.replaceChildren();
    if (projects.length === 0) {
      const item = document.createElement("li");
      item.textContent = "No projects yet.";
      els.projectList.append(item);
      return;
    }
    for (const project of projects) {
      const item = document.createElement("li");
      const button = document.createElement("button");
      button.type = "button";
      button.textContent = project;
      if (project === state.project) {
        button.classList.add("active");
      }
      button.addEventListener("click", () => openProject(project));
      item.append(button);
      els.projectList.append(item);
    }
  }

  function renderVersions(versions) {
    els.versionList.replaceChildren();
    for (const entry of versions) {
      const version = entry.name;
      const item = document.createElement("li");
      const row = document.createElement("div");
      row.style.display = "flex";
      row.style.gap = "0.5rem";
      row.style.alignItems = "center";

      const button = document.createElement("button");
      button.type = "button";
      button.textContent = entry.latest ? `${version} (latest)` : version;
      if (version === state.version) {
        button.classList.add("active");
      }
      button.addEventListener("click", () => openVersion(version));
      row.append(button);

      if (isAdmin()) {
        const del = document.createElement("button");
        del.type = "button";
        del.className = "danger";
        del.textContent = "Delete";
        del.addEventListener("click", async () => {
          if (!window.confirm(`Delete version ${version} and all of its files?`)) {
            return;
          }
          await api(
            `/projects/${encodeURIComponent(state.project)}/versions/${encodeURIComponent(version)}`,
            { method: "DELETE" },
          );
          showToast(`Deleted version ${version}`);
          if (state.version === version) {
            state.version = null;
            renderFiles([]);
          }
          await openProject(state.project);
        });
        row.append(del);
      }

      item.append(row);
      els.versionList.append(item);
    }
  }

  function formatUploadedAt(iso) {
    if (!iso) {
      return "";
    }
    const date = new Date(iso);
    if (Number.isNaN(date.getTime()) || date.getTime() === 0) {
      return "";
    }
    return date.toLocaleString();
  }

  function renderFiles(files) {
    els.fileList.replaceChildren();
    els.versionLabel.textContent = state.version
      ? `Version ${state.version}`
      : "Pick a version.";
    if (!state.version) {
      return;
    }
    if (files.length === 0) {
      const item = document.createElement("li");
      item.textContent = "No files in this version.";
      els.fileList.append(item);
      return;
    }
    for (const file of files) {
      const fileName = file.name;
      const item = document.createElement("li");
      const row = document.createElement("div");
      row.style.display = "flex";
      row.style.gap = "0.5rem";
      row.style.alignItems = "center";

      const link = document.createElement("a");
      link.href = fileUrl(state.project, state.version, fileName);
      link.textContent = fileName;
      link.addEventListener("click", async (event) => {
        event.preventDefault();
        const response = await api(link.href);
        const blob = await response.blob();
        const url = URL.createObjectURL(blob);
        const anchor = document.createElement("a");
        anchor.href = url;
        anchor.download = fileName.split("/").pop() || fileName;
        anchor.click();
        URL.revokeObjectURL(url);
      });
      row.append(link);

      const uploaded = formatUploadedAt(file.uploadedAt);
      if (uploaded) {
        const stamp = document.createElement("span");
        stamp.className = "meta";
        stamp.textContent = uploaded;
        row.append(stamp);
      }

      if (isAdmin()) {
        const del = document.createElement("button");
        del.type = "button";
        del.className = "danger";
        del.textContent = "Delete";
        del.addEventListener("click", async () => {
          await api(fileUrl(state.project, state.version, fileName), { method: "DELETE" });
          showToast(`Deleted ${fileName}`);
          await openVersion(state.version);
        });
        row.append(del);
      }

      item.append(row);
      els.fileList.append(item);
    }
  }

  async function refreshProjects() {
    const data = await api("/projects");
    state.userId = data.userId;
    state.role = data.role;
    renderSession();
    renderProjects(data.projects);
  }

  async function openProject(project) {
    state.project = project;
    state.version = null;
    clearPending();
    els.emptyState.hidden = true;
    els.projectDetail.hidden = false;
    els.projectTitle.textContent = project;
    document.getElementById("upload-version").value = "";
    await refreshProjects();
    const data = await api(`/projects/${encodeURIComponent(project)}/versions`);
    renderVersions(data.versions);
    renderFiles([]);
  }

  async function openVersion(version) {
    state.version = version;
    document.getElementById("upload-version").value = version;
    const versions = await api(`/projects/${encodeURIComponent(state.project)}/versions`);
    renderVersions(versions.versions);
    const files = await api(
      `/projects/${encodeURIComponent(state.project)}/versions/${encodeURIComponent(version)}/files`,
    );
    renderFiles(files.files);
  }

  async function uploadPending() {
    if (!state.project) {
      showToast("Choose a project first.");
      return;
    }
    const version = document.getElementById("upload-version").value.trim();
    if (!version) {
      showToast("Enter a version.");
      return;
    }
    if (state.pending.length === 0) {
      showToast("Drop or choose files first.");
      return;
    }
    for (const item of state.pending) {
      const body = new FormData();
      body.append("file", item.file, item.relativePath);
      await api(
        `/projects/${encodeURIComponent(state.project)}/versions/${encodeURIComponent(version)}/files`,
        { method: "POST", body },
      );
    }
    const count = state.pending.length;
    clearPending();
    showToast(`Uploaded ${count} file${count === 1 ? "" : "s"}`);
    await openProject(state.project);
    await openVersion(version);
  }

  async function enterWorkspace(token = null) {
    state.token = token;
    state.project = null;
    state.version = null;
    clearPending();
    els.emptyState.hidden = false;
    els.projectDetail.hidden = true;
    await refreshProjects();
    await refreshUsers();
  }

  els.loginForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    els.loginError.hidden = true;
    try {
      const data = await api("/login", {
        method: "POST",
        json: {
          userId: document.getElementById("user-id").value.trim(),
          password: document.getElementById("password").value,
        },
      });
      // Cookie holds the JWT; GET /projects establishes the signed-in UI.
      await enterWorkspace(data.token);
    } catch (error) {
      els.loginError.textContent = "Sign-in failed. Check user id and password.";
      els.loginError.hidden = false;
    }
  });

  els.signOut.addEventListener("click", async () => {
    try {
      await api("/logout", { method: "POST" });
    } catch (_error) {
      // Local sign-out still proceeds if logout fails.
    }
    clearSession();
    clearPending();
    els.loginForm.reset();
    els.passwordForm.reset();
    renderSession();
  });

  els.passwordForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    const currentPassword = document.getElementById("current-password").value;
    const newPassword = document.getElementById("new-password").value;
    try {
      await api("/password", {
        method: "POST",
        json: { currentPassword, newPassword },
      });
      els.passwordForm.reset();
      showToast("Password updated");
    } catch (error) {
      showToast(error.message || "Password change failed.");
    }
  });

  els.enrollForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    const userId = document.getElementById("enroll-user-id").value.trim();
    const password = document.getElementById("enroll-password").value;
    const role = document.getElementById("enroll-role").value;
    try {
      await api("/users", {
        method: "POST",
        json: { userId, password, role },
      });
      els.enrollForm.reset();
      document.getElementById("enroll-role").value = "Client";
      showToast(`Enrolled ${userId}`);
      await refreshUsers();
    } catch (error) {
      showToast(error.message || "Enrollment failed.");
    }
  });

  els.grantForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    const project = document.getElementById("grant-project").value.trim();
    const userId = document.getElementById("grant-user").value.trim();
    try {
      await api(`/projects/${encodeURIComponent(project)}/access`, {
        method: "POST",
        json: { userId },
      });
      els.grantForm.reset();
      showToast(`Authorized ${userId} for ${project}`);
      await refreshProjects();
    } catch (error) {
      showToast(error.message || "Grant failed. User must exist.");
    }
  });

  els.uploadForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    try {
      await uploadPending();
    } catch (error) {
      showToast(error.message || "Upload failed.");
    }
  });

  ["dragenter", "dragover"].forEach((type) => {
    els.dropZone.addEventListener(type, (event) => {
      event.preventDefault();
      event.stopPropagation();
      els.dropZone.classList.add("dragover");
    });
  });

  ["dragleave", "drop"].forEach((type) => {
    els.dropZone.addEventListener(type, (event) => {
      event.preventDefault();
      event.stopPropagation();
      if (type === "dragleave" && els.dropZone.contains(event.relatedTarget)) {
        return;
      }
      els.dropZone.classList.remove("dragover");
    });
  });

  els.dropZone.addEventListener("drop", async (event) => {
    try {
      const entries = await collectFromDataTransfer(event.dataTransfer);
      queueFiles(entries);
    } catch (error) {
      showToast("Could not read dropped files.");
    }
  });

  els.pickFiles.addEventListener("click", (event) => {
    event.stopPropagation();
    els.uploadFile.click();
  });

  els.pickFolder.addEventListener("click", (event) => {
    event.stopPropagation();
    els.uploadFolder.click();
  });

  els.uploadFile.addEventListener("change", () => {
    queueFiles(collectFromFileList(els.uploadFile.files, false));
    els.uploadFile.value = "";
  });

  els.uploadFolder.addEventListener("change", () => {
    queueFiles(collectFromFileList(els.uploadFolder.files, true));
    els.uploadFolder.value = "";
  });

  for (const card of els.railCards) {
    card.addEventListener("toggle", () => {
      if (!card.open) {
        return;
      }
      for (const other of els.railCards) {
        if (other !== card) {
          other.open = false;
        }
      }
    });
  }

  async function bootstrap() {
    try {
      // Cookie JWT + the same projects fetch used after login.
      await enterWorkspace();
    } catch (_error) {
      renderSession();
    }
  }

  bootstrap();
})();
