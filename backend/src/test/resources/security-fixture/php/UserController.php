<?php
class UserController {
    public function show($db, $conn, $request) {
        $name = $_GET['name'];
        $db->query("SELECT * FROM users WHERE name = '" . $name . "'"); // expect: cia.php.sql-injection.concatenated-query
        $db->query("SELECT * FROM users WHERE name = '$name'"); // expect: cia.php.sql-injection.concatenated-query
        $stmt = $db->prepare("SELECT * FROM users WHERE name = ?"); // ok
        DB::select("SELECT * FROM users WHERE name = ?", [$name]); // ok
        User::whereRaw("name = '" . $name . "'")->get(); // expect: cia.php.sql-injection.concatenated-query
        exec($request->input('cmd')); // expect: cia.php.injection.command-execution
        exec("ls -l"); // ok
        system("ls " . escapeshellarg($name)); // ok
        eval($name); // expect: cia.php.injection.eval
        unserialize($_COOKIE['cart']); // expect: cia.php.deserialization.unserialize
        json_decode($_COOKIE['cart'], true); // ok
        echo $_GET['q']; // expect: cia.php.xss.echo-request
        echo htmlspecialchars($_GET['q']); // ok
        $hash = md5($name); // expect: cia.php.crypto.weak-hash
        $hash = password_hash($name, PASSWORD_DEFAULT); // ok
        curl_setopt($conn, CURLOPT_SSL_VERIFYPEER, false); // expect: cia.php.tls.verification-disabled
        curl_setopt($conn, CURLOPT_SSL_VERIFYPEER, true); // ok
    }
}
