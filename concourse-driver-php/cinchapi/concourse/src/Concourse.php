<?php
require_once dirname(__FILE__) . "/autoload.php";

use Thrift\Transport\TSocket;
use Thrift\Transport\TBufferedTransport;
use Thrift\Protocol\TBinaryProtocolAccelerated;
use Thrift\ConcourseServiceClient;
use Thrift\Data\TObject;
use Thrift\Shared\TransactionToken;
use Thrift\Shared\Type;
use Thrift\Shared\AccessToken;

/**
* Concourse is a self-tuning database that makes it easier for developers to
* quickly build robust and scalable systems. Concourse dynamically adapts on a
* per-application basis and offers features like automatic indexing, version
* control, and distributed ACID transactions within a big data platform that
* reduces operational complexity. Concourse abstracts away the management and
* tuning aspects of the database and allows developers to focus on what really
* matters.
*/
class Concourse {

    /**
    * Create a new client connection to the specified environment of the
    * specified Concourse Server and return a handle to facilitate interaction.
    * @param type $host
    * @param type $port
    * @param type $username
    * @param type $password
    * @param type $environment
    * @return \Concourse
    */
    public static function connect($host = "localhost", $port = 1717,
    $username = "admin", $password = "admin", $environment = "") {
        return new Concourse($host, $port, $username, $password, $environment);
    }

    private $host;
    private $port;
    private $username;
    private $password;
    private $environment;
    private $client;
    private $transaction;

    /**
    * Initialize a new client connection.
    * @param type $host
    * @param type $port
    * @param type $username
    * @param type $password
    * @param type $environment
    * @throws Exception
    */
    private function __construct($host="localhost", $port=1717, $username="admin", $password="admin", $environment="") {
        $kwargs = func_get_arg(0);
        if(is_array($kwargs)){
            $host = "localhost";
            $prefs = find_in_kwargs_by_alias("prefs", $kwargs);
            if(!empty($prefs)){
                $prefs = parse_ini_file(expand_path($prefs));
            }
            else{
                $prefs = [];
            }
        }
        else{
            $kwargs = [];
            $prefs = [];
        }
        // order of precedence for args: prefs -> kwargs -> positional -> default
        $this->host = $prefs["host"] ?: $kwargs["host"] ?: $host;
        $this->port = $prefs["port"] ?: $kwargs["port"] ?: $port;
        $this->username = $prefs["username"] ?: find_in_kwargs_by_alias('username', $kwargs) ?: $username;
        $this->password = $prefs["password"] ?: find_in_kwargs_by_alias("password", $kwargs) ?: $passwords;
        $this->environment = $prefs["environment"] ?: $kwargs["environment"] ?: $environment;
        try {
            $socket = new TSocket($this->host, $this->port);
            $transport = new TBufferedTransport($socket);
            $protocol = new TBinaryProtocolAccelerated($transport);
            $this->client = new ConcourseServiceClient($protocol);
            $transport->open();
            $this->authenticate();
        }
        catch (TException $e) {
            throw new Exception("Could not connect to the Concourse Server at "
            . $this->host . ":" . $this->port);
        }
    }

    /**
    * Login with the username and password and locally store the AccessToken
    * to use with subsequent CRUD methods.
    * @throws Exception
    */
    private function authenticate() {
        try {
            $this->creds = $this->client->login($this->username, $this->password,
            $this->environment);
        }
        catch (TException $e) {
            throw e;
        }
    }

    /**
    * Abort the current transaction and discard any changes that were staged.
    * After returning, the driver will work in autocommit mode where all subsequent
    * changes will be committed immediately until another transaction is started.
    */
    public function abort() {
        if(!empty($this->transaction)){
            $token = $this->transaction;
            $this->transaction = null;
            $this->client->abort($this->creds, $token, $this->environment);
        }
    }

    /**
    * Append a value to a key within a record if it does not currently exist.
    * @param string $key
    * @param object $value
    * @param mixed $records
    * @return mixed
    * @throws InvalidArgumentException
    */
    public function add($key, $value=null, $records=null) {
        $kwargs = func_get_arg(0);
        if(is_array($kwargs)){
            $key = null;
        }
        else{
            $kwargs = null;
        }
        $key = $kwargs['key'] ?: $key;
        $value = $kwargs['value'] ?: $value;
        $records = $kwargs['record'] ?: $kwargs['records'] ?: $records;
        $value = Convert::phpToThrift($value);
        if ($key && $value && empty($records)) {
            return $this->client->addKeyValue($key, $value, $this->creds,
            $this->transaction, $this->environment);
        }
        else if($key && $value && is_array($records)){
            return $this->client->addKeyValueRecords($key, $value, $records,
            $this->creds, $this->transaction, $this->environment);
        }
        else if($key && $value && is_int($records) || is_long($records)){
            return $this->client->addKeyValueRecord($key, $value, $records,
            $this->creds, $this->transaction, $this->environment);
        }
        else {
            require_arg('key and record(s)');
        }
    }

    /**
    * Return a log of revisions.
    * @param string $key
    * @param mixed $start
    * @param mixed $end
    * @return mixed
    */
    public function audit($key=null, $record=null, $start=null, $end=null){
        $kwargs = func_get_arg(0);
        if(is_array($kwargs)){
            $key = null;
        }
        else{
            $kwargs = null;
        }
        $key = $kwargs['key'] ?: $key;
        $record = $kwargs['record'] ?: $record;
        $start =  $start ?: $kwargs['start'] ?: find_in_kwargs_by_alias('timestamp', $kwargs);
        $end =  $end ?: $kwargs['end'];
        $startstr = is_string($start);
        $endstr = is_string($end);
        if(is_int($key) || is_long($key)){
            $record = $key;
            $key = null;
        }
        if(!empty($key) && !empty(record) && !empty($start) && !$startstr &&
        !empty($end) && !$endstr){
            $data = $this->client->auditKeyRecordStartEnd($key, $record, $start,
            $end, $this->creds, $this->transaction, $this->environment);
        }
        else if(!empty($key) && !empty(record) && !empty($start) && $startstr &&
        !empty($end) && $endstr){
            $data = $this->client->auditKeyRecordStartstrEndstr($key, $record,
            $start, $end, $this->creds, $this->transaction, $this->environment);
        }
        else if(!empty($key) && !empty($record) && !empty($start) && !$startstr){
            $data = $this->client->auditKeyRecordStart($key, $record, $start,
            $this->creds, $this->transaction, $this->environment);
        }
        else if(!empty($key) && !empty($record) && !empty($start) && $startstr){
            $data = $this->client->auditKeyRecordStartstr($key, $record, $start,
            $this->creds, $this->transaction, $this->environment);
        }
        else if(!empty($key) && !empty($record)){
            $data = $this->client->auditKeyRecord($key, $record, $this->creds,
            $this->transaction, $this->environment);
        }
        else if(!empty($record) && !empty($start) && !$startstr && !empty($end)
        && $endstr){
            $data = $this->client->auditRecordStartEnd($record, $start, $end,
            $this->creds, $this->transaction, $this->environment);
        }
        else if(!empty($record) && !empty($start) && !$start && !empty($end) && $end){
            $data = $this->client->auditRecordStartstrEndstr($record, $start, $end, $this->creds, $this->transaction, $this->environment);
        }
        else if(!empty($record) && !empty($start) && !$startstr){
            $data = $this->client->auditRecordStart($record, $start, $this->creds, $this->transaction, $this->environment);
        }
        else if(!empty($record) && !empty($start) && $startstr){
            $data = $this->client->auditRecordStartstr($record, $start, $this->creds, $this->transaction, $this->environment);
        }
        else if(!empty($record)){
            $data = $this->client->auditRecord($record, $this->creds, $this->transaction, $this->environment);
        }
        else{
            require_arg('record');
        }
        return $data;
    }

    public function logout(){

    }

    /**
    *
    * @param type $keys
    * @param type $timestamp
    * @return type
    */
    public function browse($keys=null, $timestamp=null){
        $array = func_get_arg(0);
        if(is_array($array)){
            $keys = $array['keys'];
            $keys = empty($keys) ? $array['key'] : $keys;
            $timestamp = $array['timestamp'];
        }
        $timestamp = is_string($timestamp) ? Convert::stringToTime($timestamp):
        $timestamp;
        if(is_array($keys) && !empty($timestamp)){
            $data = $this->client->browseKeysTime($keys, $timestamp, $this->creds,
            $this->transaction, $this->environment);

        }
        else if(is_array($keys)){
            $data = $this->client->browseKeys($keys, $this->creds,
            $this->transaction, $this->environment);
        }
        else if(!empty($timestamp)){
            $data = $this->client->browseKeyTime($keys, $timestamp, $this->creds,
            $this->transaction, $this->environment);
        }
        else{
            $data = $this->client->browseKey($keys, $this->creds,
            $this->transaction, $this->environment);
        }
        return Convert::phpify($data);
    }

    public function get($keys=null, $criteria=null, $records=null, $timestamp=null){
        $kwargs = func_get_arg(0);
        if(is_array($kwargs)){
            $keys = null;
        }
        else{
            $kwargs = null;
        }
        $keys = $keys ?: $kwargs['key'] ?: $kwargs['keys'];
        $criteria = $criteria ?: find_in_kwargs_by_alias('criteria', $kwargs);
        $records = $records ?: $kwargs['record'] ?: $kwargs['records'];
        $timestamp = $timestamp ?: find_in_kwargs_by_alias('timestamp', $kwargs);
        $data = $this->client->getKeyRecord($keys, $records, $this->creds,
        $this->transaction, $this->environment);
        return Convert::thriftToPhp($data);
    }

    public function set($key=null, $value=null, $records=null){
        $kwargs = func_get_arg(0);
        if(is_array($kwargs)){
            $keys = null;
        }
        else{
            $kwargs = null;
        }
        $key = $key ?: $kwargs['key'];
        $value = $value ?: $kwargs['value'];
        $records = $records ?: $kwargs['record'] ?: $kwargs['records'];
        $value = Convert::phpToThrift($value);
        if(empty($records)){
            return $this->client->setKeyValue($key, $value, $this->creds, $this->transaction, $this->environment);
        }
        else if(is_array($records)){
            $this->client->setKeyValueRecords($key, $value, $records, $this->creds, $this->transaction, $this->environment);
        }
        else if(is_int($records) || is_long($records)){
            $this->client->setKeyValueRecord($key, $value, $records, $this->creds, $this->transaction, $this->environment);
        }
        else{
            require_arg('record or records');
        }
    }

    /**
    * Start a new transaction.
    */
    public function stage(){
        $this->transaction = $this->client->stage($this->creds, $this->environment);
    }

    public function time($phrase=null){
        if(!empty($phrase)){
            return $this->client->timePhrase($phrase, $this->creds, $this->transaction, $this->environment);
        }
        else{
            return $this->client->time($this->creds, $this->transaction, $this->environment);
        }
    }

}
