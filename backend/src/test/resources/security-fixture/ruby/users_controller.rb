class UsersController < ApplicationController
  def show
    User.where("name = '#{params[:name]}'") # expect: cia.ruby.sql-injection.interpolated-query
    User.where("name = ?", params[:name]) # ok
    User.where(name: params[:name]) # ok
    ActiveRecord::Base.connection.execute("DELETE FROM users WHERE id = #{params[:id]}") # expect: cia.ruby.sql-injection.interpolated-query
    system("convert #{params[:file]} out.png") # expect: cia.ruby.injection.command-execution
    system("convert", params[:file], "out.png") # ok
    eval(params[:code]) # expect: cia.ruby.injection.eval
    Marshal.load(cookies[:cart]) # expect: cia.ruby.deserialization.unsafe-load
    YAML.safe_load(cookies[:cart]) # ok
    @bio = params[:bio].html_safe # expect: cia.ruby.xss.unescaped-output
    @bio = sanitize(params[:bio]) # ok
    Digest::MD5.hexdigest(params[:password]) # expect: cia.ruby.crypto.weak-hash
    Digest::SHA256.hexdigest(params[:password]) # ok
    http = Net::HTTP.new("example.com", 443)
    http.verify_mode = OpenSSL::SSL::VERIFY_NONE # expect: cia.ruby.tls.verification-disabled
    http.verify_mode = OpenSSL::SSL::VERIFY_PEER # ok
  end
end
