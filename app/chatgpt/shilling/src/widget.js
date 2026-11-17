import { App, applyDocumentTheme, applyHostStyleVariables } from "@modelcontextprotocol/ext-apps";
import { OpenAIExtensions } from "@openai/mcp-extensions/app";
import { pages, pageFromPath } from "./config.mjs";

const config = JSON.parse(document.getElementById("config").textContent);
const frame = document.getElementById("client");
const status = document.getElementById("status");
const app = new App({ name: "Shilling", version: "0.1.0" });
const extensions = new OpenAIExtensions(app);
let currentPage = "home";
let started = false;

function navigate(page) {
  if (!pages.includes(page)) return;
  currentPage = page;
  if (!started) {
    frame.src = `${config.origin}/client/#/${page}`;
    started = true;
  } else {
    frame.contentWindow.postMessage({ type: "shilling:navigate", page }, config.origin);
  }
}
frame.addEventListener("load", () => {
  frame.contentWindow.postMessage({ type: "shilling:navigate", page: currentPage }, config.origin);
});
window.addEventListener("message", (event) => {
  if (event.source === frame.contentWindow && event.origin === config.origin && event.data?.type === "shilling:ready") {
    frame.contentWindow.postMessage({ type: "shilling:navigate", page: currentPage }, config.origin);
  }
});

app.ontoolresult = (result) => {
  navigate(result.structuredContent?.page || "home");
};
function hostChanged() {
  const context = app.getHostContext();
  if (context?.theme) applyDocumentTheme(context.theme);
  if (context?.styles?.variables) applyHostStyleVariables(context.styles.variables);
  const link = extensions.deepLink.getCurrent();
  if (link) navigate(pageFromPath(link.url));
}
app.addEventListener("hostcontextchanged", hostChanged);
document.getElementById("reload").addEventListener("click", () => {
  frame.src = `${config.origin}/client/#/${currentPage}`;
});
document.getElementById("open").addEventListener("click", () => {
  app.openLink({ url: `${config.origin}/client/#/${currentPage}` }).catch(() => {
    status.textContent = "Open Shilling at " + config.origin + "/client/ in your browser.";
  });
});
app.connect().then(() => {
  hostChanged();
  if (!started) navigate("home");
}).catch(() => {
  status.textContent = "The ChatGPT connection failed. Reopen Shilling from the plugin menu.";
});
