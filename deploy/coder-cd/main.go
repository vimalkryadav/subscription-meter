// coder-cd runs the stock Coder CLI with a narrowly scoped workspace metadata workaround.
package main

import (
	"context"
	"crypto/subtle"
	"crypto/tls"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"os/exec"
	"os/signal"
	"regexp"
	"strings"
	"syscall"
	"time"
)

const workspaceID = "6b76a990-b67e-44bd-a37e-ab2fa6c66650"
const deploymentOrigin = "https://coder.scaler.com"
const receiver = "/home/coder/control-usage-deploy/scripts/receive_release.sh"

func workspaceQuery(method, path string, query url.Values) url.Values {
	if method == http.MethodGet && path == "/api/v2/workspaces/"+workspaceID {
		query.Set("include_related", "latest_build.*")
	}
	return query
}

func childEnvironment(env []string, localURL, token, config string) []string {
	result := make([]string, 0, len(env)+4)
	for _, value := range env {
		if !strings.HasPrefix(value, "CODER_") {
			result = append(result, value)
		}
	}
	return append(result, "CODER_URL="+localURL, "CODER_SESSION_TOKEN="+token, "CODER_CONFIG_DIR="+config, "CODER_USE_KEYRING=false")
}

// DERP can skip the HTTP101 response (fast start). Preserve its byte stream
// rather than asking an HTTP reverse proxy to parse encrypted relay frames.
func relay(w http.ResponseWriter, r *http.Request, origin *url.URL) {
	dialer := &tls.Dialer{NetDialer: &net.Dialer{Timeout: 10 * time.Second}, Config: &tls.Config{ServerName: origin.Hostname(), MinVersion: tls.VersionTLS12}}
	upstream, err := dialer.DialContext(r.Context(), "tcp", net.JoinHostPort(origin.Hostname(), "443"))
	if err != nil {
		http.Error(w, "Coder relay unavailable", http.StatusBadGateway)
		return
	}
	defer upstream.Close()
	request := r.Clone(r.Context())
	request.URL.Scheme = ""
	request.URL.Host = ""
	request.Host = origin.Host
	pipeRelay(w, request, upstream)
}

func pipeRelay(w http.ResponseWriter, r *http.Request, upstream net.Conn) {
	_ = upstream.SetWriteDeadline(time.Now().Add(10 * time.Second))
	if err := r.Write(upstream); err != nil {
		http.Error(w, "Coder relay unavailable", http.StatusBadGateway)
		return
	}
	_ = upstream.SetWriteDeadline(time.Time{})
	hijacker, ok := w.(http.Hijacker)
	if !ok {
		http.Error(w, "Relay unavailable", http.StatusInternalServerError)
		return
	}
	client, buffer, err := hijacker.Hijack()
	if err != nil {
		return
	}
	defer client.Close()
	done := make(chan struct{}, 2)
	go func() { _, _ = io.Copy(upstream, buffer.Reader); done <- struct{}{} }()
	go func() { _, _ = io.Copy(client, upstream); done <- struct{}{} }()
	select {
	case <-done:
	case <-r.Context().Done():
	}
}

func handler(origin *url.URL, token string, trace bool) http.Handler {
	proxy := &httputil.ReverseProxy{
		ModifyResponse: func(response *http.Response) error {
			if trace {
				fmt.Fprintf(os.Stderr, "Coder API %s %.200q -> %d\n", response.Request.Method, response.Request.URL.Path, response.StatusCode)
			}
			return nil
		},
		Rewrite: func(request *httputil.ProxyRequest) {
			request.SetURL(origin)
			request.Out.URL.RawQuery = workspaceQuery(request.In.Method, request.In.URL.Path, request.Out.URL.Query()).Encode()
			request.Out.Host = origin.Host
		},
		ErrorLog: log.New(io.Discard, "", 0),
		ErrorHandler: func(w http.ResponseWriter, r *http.Request, err error) {
			http.Error(w, "Coder transport unavailable", http.StatusBadGateway)
		},
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if trace {
			fmt.Fprintf(os.Stderr, "Coder proxy received %s %.200q\n", r.Method, r.URL.Path)
		}
		isRelay := (r.URL.Path == "/derp" || r.URL.Path == "/derp/latency-check") && (r.Method == http.MethodGet || r.Method == http.MethodHead)
		if !isRelay && (!strings.HasPrefix(r.URL.Path, "/api/v2/") || subtle.ConstantTimeCompare([]byte(r.Header.Get("Coder-Session-Token")), []byte(token)) != 1) {
			http.NotFound(w, r)
			return
		}
		if isRelay && (r.ContentLength > 0 || len(r.TransferEncoding) != 0) {
			http.Error(w, "Relay request body is not allowed", http.StatusBadRequest)
			return
		}
		if r.URL.Path == "/derp" && r.Method == http.MethodGet {
			relay(w, r, origin)
			return
		}
		proxy.ServeHTTP(w, r)
	})
}

func readToken(path string) (string, error) {
	info, err := os.Lstat(path)
	if err != nil || !info.Mode().IsRegular() || info.Mode().Perm()&0077 != 0 {
		return "", errors.New("Scoped token must be a private regular file (0600)")
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		return "", errors.New("Cannot read scoped token file")
	}
	token := strings.TrimSpace(string(raw))
	if token == "" || strings.ContainsAny(token, "\r\n") {
		return "", errors.New("Invalid scoped token file")
	}
	return token, nil
}

func run() error {
	coder := flag.String("coder", "coder", "Path to the deployment-matched stock Coder CLI")
	tokenFile := flag.String("token-file", "", "Private file containing only the scoped automation token")
	sha := flag.String("sha", "", "Full reviewed main commit")
	image := flag.String("image", "", "Immutable canonical GHCR digest")
	probe := flag.Bool("probe", false, "Execute only true in the exact workspace to verify scoped transport")
	flag.Parse()
	if flag.NArg() != 0 {
		return errors.New("Unexpected arguments")
	}
	if !*probe && (!regexp.MustCompile(`^[0-9a-f]{40}$`).MatchString(*sha) || !regexp.MustCompile(`^ghcr\.io/vimalkryadav/control-usage@sha256:[0-9a-f]{64}$`).MatchString(*image)) {
		return errors.New("Full main SHA and immutable control-usage GHCR digest required")
	}
	token, err := readToken(*tokenFile)
	if err != nil {
		return err
	}
	config, err := os.MkdirTemp("", "control-usage-coder-config-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(config)
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return err
	}
	origin, _ := url.Parse(deploymentOrigin)
	server := &http.Server{Handler: handler(origin, token, *probe), ReadHeaderTimeout: 10 * time.Second}
	go func() { _ = server.Serve(listener) }()
	defer server.Close()
	args := []string{"ssh", "--disable-autostart", "--wait=no", "--usage-app=disable", workspaceID, "--"}
	if *probe {
		args = append(args, "true")
	} else {
		args = append(args, receiver, *sha, *image)
	}
	signals, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	duration := 15 * time.Minute
	if *probe {
		duration = time.Minute
	}
	ctx, cancel := context.WithTimeout(signals, duration)
	defer cancel()
	command := exec.CommandContext(ctx, *coder, args...)
	command.Env = childEnvironment(os.Environ(), "http://"+listener.Addr().String(), token, config)
	command.Stdin = os.Stdin
	command.Stdout = os.Stdout
	command.Stderr = os.Stderr
	if err = command.Run(); err != nil {
		return errors.New("Scoped Coder command failed")
	}
	return nil
}

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
