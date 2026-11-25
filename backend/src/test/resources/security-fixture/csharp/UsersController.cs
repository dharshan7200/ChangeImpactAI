using System.Diagnostics;
using System.Data.SqlClient;
using System.Net.Http;
using System.Runtime.Serialization.Formatters.Binary;
using System.Security.Cryptography;

public class UsersController
{
    private const string ApiKey = "sk-live-1234567890"; // expect: cia.csharp.secrets.hardcoded-credential
    private readonly string _settingName = "UserList"; // ok

    public void Show(SqlConnection conn, AppDbContext db, string name, string cmd)
    {
        var a = new SqlCommand("SELECT * FROM Users WHERE Name = '" + name + "'", conn); // expect: cia.csharp.sql-injection.concatenated-query
        var b = new SqlCommand($"SELECT * FROM Users WHERE Name = '{name}'", conn); // expect: cia.csharp.sql-injection.concatenated-query
        var c = new SqlCommand("SELECT * FROM Users WHERE Name = @name", conn); // ok
        db.Users.FromSqlRaw("SELECT * FROM Users WHERE Name = '" + name + "'"); // expect: cia.csharp.sql-injection.concatenated-query
        db.Users.FromSqlInterpolated($"SELECT * FROM Users WHERE Name = {name}"); // ok
        Process.Start(cmd); // expect: cia.csharp.injection.command-execution
        Process.Start("notepad.exe"); // ok
        var f = new BinaryFormatter(); // expect: cia.csharp.deserialization.binary-formatter
        var h = MD5.Create(); // expect: cia.csharp.crypto.weak-algorithm
        var s = SHA256.Create(); // ok
        var handler = new HttpClientHandler();
        handler.ServerCertificateCustomValidationCallback = (m, c2, ch, e) => true; // expect: cia.csharp.tls.verification-disabled
    }
}
