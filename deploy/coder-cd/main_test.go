package main

import (
	"bufio"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestOnlyExactWorkspaceGETExcludesTemplate(t *testing.T) {
	for _, tc := range []struct {
		method, path string
		rewrite      bool
	}{
		{"GET", "/api/v2/workspaces/" + workspaceID, true},
		{"POST", "/api/v2/workspaces/" + workspaceID, false},
		{"GET", "/api/v2/workspaces/other", false},
		{"GET", "/api/v2/workspaces/" + workspaceID + "/watch", false},
	} {
		q := url.Values{"include_related": {"template"}, "include_deleted": {"false"}}
		got := workspaceQuery(tc.method, tc.path, q)
		expected := "template"
		if tc.rewrite {
			expected = "latest_build.*"
		}
		if got.Get("include_related") != expected || got.Get("include_deleted") != "false" {
			t.Fatal("Unexpected metadata query rewrite")
		}
	}
}

func TestUntrustedRequestsNeverReachUpstream(t *testing.T) {
	calls := 0
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { calls++; w.WriteHeader(204) }))
	defer upstream.Close()
	target, _ := url.Parse(upstream.URL)
	proxy := httptest.NewServer(handler(target, "scoped", false))
	defer proxy.Close()
	for _, tc := range []struct{ path, token string }{{"/api/v2/workspaces/" + workspaceID, ""}, {"/api/v2/appearance", "wrong"}, {"/unexpected", "scoped"}, {"/derp/other", "scoped"}} {
		req, _ := http.NewRequest("GET", proxy.URL+tc.path, nil)
		req.Header.Set("Coder-Session-Token", tc.token)
		response, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		response.Body.Close()
		if response.StatusCode != 404 {
			t.Fatal("Unauthorized proxy request accepted")
		}
	}
	if calls != 0 {
		t.Fatal("Unauthorized request forwarded")
	}
	req, _ := http.NewRequest("GET", proxy.URL+"/api/v2/appearance", nil)
	req.Header.Set("Coder-Session-Token", "scoped")
	response, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != 204 || calls != 1 {
		t.Fatal("Authorized request did not reach configured origin")
	}
}

func TestPersonalCoderConfigurationCannotLeakIntoChild(t *testing.T) {
	env := childEnvironment([]string{"PATH=/bin", "CODER_SESSION_TOKEN=personal", "CODER_URL=https://wrong", "CODER_CONFIG_DIR=/private", "CODER_HEADER=secret"}, "http://127.0.0.1:1", "scoped", "/temporary")
	text := strings.Join(env, "\n")
	if strings.Contains(text, "personal") || strings.Contains(text, "/private") || strings.Contains(text, "CODER_HEADER") {
		t.Fatal("Inherited Coder credentials/config retained")
	}
	if !strings.Contains(text, "CODER_SESSION_TOKEN=scoped") || !strings.Contains(text, "CODER_USE_KEYRING=false") {
		t.Fatal("Scoped isolated context missing")
	}
}

func TestPrivateTokenFileRequired(t *testing.T) {
	path := filepath.Join(t.TempDir(), "token")
	if err := os.WriteFile(path, []byte("scoped-token\n"), 0600); err != nil {
		t.Fatal(err)
	}
	if token, err := readToken(path); err != nil || token != "scoped-token" {
		t.Fatal("Private token rejected")
	}
	if err := os.Chmod(path, 0644); err != nil {
		t.Fatal(err)
	}
	if _, err := readToken(path); err == nil {
		t.Fatal("Readable-by-others token accepted")
	}
	link := filepath.Join(t.TempDir(), "token-link")
	if err := os.Symlink(path, link); err != nil {
		t.Fatal(err)
	}
	if _, err := readToken(link); err == nil {
		t.Fatal("Symlink accepted")
	}
}

func TestDERPPreservesRawBidirectionalFrames(t *testing.T) {
	for _, fastStart := range []bool{true, false} {
		t.Run(map[bool]string{true: "fast-start", false: "standard-upgrade-buffered-client-frame"}[fastStart], func(t *testing.T) { testDERPFrames(t, fastStart) })
	}
}

func testDERPFrames(t *testing.T, fastStart bool) {
	upstream, remote := net.Pipe()
	defer upstream.Close()
	defer remote.Close()
	_ = upstream.SetDeadline(time.Now().Add(5 * time.Second))
	_ = remote.SetDeadline(time.Now().Add(5 * time.Second))
	received := make(chan string, 1)
	go func() {
		reader := bufio.NewReader(remote)
		request, err := http.ReadRequest(reader)
		if err != nil {
			received <- "request-error"
			return
		}
		if (request.Header.Get("Derp-Fast-Start") == "1") != fastStart {
			received <- "header-error"
			return
		}
		if !fastStart {
			_, _ = io.WriteString(remote, "HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: DERP\r\n\r\n")
		}
		_, _ = remote.Write([]byte{0, 1, 2, 3})
		data := make([]byte, 3)
		_, err = io.ReadFull(reader, data)
		if err != nil {
			received <- "read-error"
			return
		}
		received <- string(data)
		remote.Close()
	}()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { pipeRelay(w, r, upstream) }))
	defer server.Close()
	address := strings.TrimPrefix(server.URL, "http://")
	client, err := net.DialTimeout("tcp", address, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	_ = client.SetDeadline(time.Now().Add(5 * time.Second))
	fastHeader := ""
	if fastStart {
		fastHeader = "Derp-Fast-Start: 1\r\n"
	}
	_, _ = io.WriteString(client, "GET /derp HTTP/1.1\r\nHost: localhost\r\nConnection: Upgrade\r\nUpgrade: DERP\r\n"+fastHeader+"\r\n")
	if !fastStart {
		_, _ = client.Write([]byte("abc"))
	}
	reader := bufio.NewReader(client)
	if !fastStart {
		response, err := http.ReadResponse(reader, nil)
		if err != nil || response.StatusCode != 101 {
			t.Fatal("Standard upgrade response not preserved")
		}
	}
	frame := make([]byte, 4)
	if _, err = io.ReadFull(reader, frame); err != nil {
		t.Fatal(err)
	}
	if string(frame) != string([]byte{0, 1, 2, 3}) {
		t.Fatal("Relay treated encrypted frame as HTTP")
	}
	if fastStart {
		_, _ = client.Write([]byte("abc"))
	}
	if got := <-received; got != "abc" {
		t.Fatalf("Bidirectional relay failed: %s", got)
	}
}

func TestRelayBodiesRejectedBeforeDial(t *testing.T) {
	origin, _ := url.Parse("https://invalid.example")
	for _, method := range []string{"GET", "HEAD"} {
		for _, chunked := range []bool{false, true} {
			r := httptest.NewRequest(method, "/derp", strings.NewReader("body"))
			if chunked {
				r.ContentLength = -1
				r.TransferEncoding = []string{"chunked"}
			}
			w := httptest.NewRecorder()
			handler(origin, "scoped", false).ServeHTTP(w, r)
			if w.Code != 400 {
				t.Fatalf("relay body accepted: %d", w.Code)
			}
		}
	}
}
