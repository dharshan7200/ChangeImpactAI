package store

import (
	"crypto/md5"
	"crypto/sha256"
	"crypto/tls"
	"database/sql"
	"fmt"
	"math/rand"
	"os/exec"
)

func Find(db *sql.DB, name string, script string) {
	db.Query(fmt.Sprintf("SELECT * FROM users WHERE name = '%s'", name)) // expect: cia.go.sql-injection.formatted-query
	db.Exec("DELETE FROM users WHERE name = '" + name + "'")            // expect: cia.go.sql-injection.formatted-query
	db.Query("SELECT * FROM users WHERE name = $1", name)               // ok
	fmt.Println(fmt.Sprintf("hello %s", name))                         // ok
	exec.Command("sh", "-c", script).Run()                             // expect: cia.go.injection.command-execution
	exec.Command("ls", name).Run()                                     // ok
	md5.Sum([]byte(name))                                              // expect: cia.go.crypto.weak-hash
	sha256.Sum256([]byte(name))                                        // ok
	_ = &tls.Config{InsecureSkipVerify: true}                          // expect: cia.go.tls.verification-disabled
	_ = &tls.Config{MinVersion: tls.VersionTLS12}                      // ok
}

func NewSessionToken() int {
	return rand.Intn(1000000) // expect: cia.go.random.insecure-for-security
}

func Shuffle() int {
	return rand.Intn(10) // ok
}
