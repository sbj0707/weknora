//go:build ignore
// Test-only exports in a temporary dependency copy. No production Asynq function is changed.
package asynq
import(
 "context";"time";"errors"
 internalcontext "github.com/hibiken/asynq/internal/context"
 "github.com/hibiken/asynq/internal/base"
 "github.com/hibiken/asynq/internal/rdb"
 "github.com/hibiken/asynq/internal/timeutil"
 redislib "github.com/redis/go-redis/v9"
)
func JavaMoveFixtureContext(ctx context.Context,retry,maxRetry int)(context.Context,context.CancelFunc){return internalcontext.New(ctx,&base.TaskMessage{ID:"transfer-task",Type:"knowledge:move",Queue:"low",Retried:retry,Retry:maxRetry},time.Now().Add(time.Minute))}
func JavaMoveFixtureNamedQueue(address string)([]map[string]any,error){
 ctx:=context.Background();redis:=redislib.NewClient(&redislib.Options{Addr:address});defer redis.Close();backend:=rdb.NewRDB(redis);clock:=timeutil.NewSimulatedClock(time.Unix(1760000000,0));backend.SetClock(clock);client:=NewClient(RedisClientOpt{Addr:address});defer client.Close();rows:=[]map[string]any{}
 enqueue:=func(name,queue,id string,keep time.Duration){_,err:=client.Enqueue(NewTask("manual:process",[]byte(`{"tenant_id":7}`)),TaskID(id),Queue(queue),Retention(keep));text:="";if err!=nil{text=err.Error()};rows=append(rows,map[string]any{"step":name,"queue":queue,"id":id,"conflict":errors.Is(err,ErrTaskIDConflict),"error":text})}
 enqueue("initial","default","move-reparse:transfer-task:doc",7*24*time.Hour)
 enqueue("pending-conflict","default","move-reparse:transfer-task:doc",7*24*time.Hour)
 msg,_,err:=backend.Dequeue("default");if err!=nil{return nil,err}
 enqueue("active-conflict","default",msg.ID,7*24*time.Hour)
 if err=backend.MarkAsComplete(ctx,msg);err!=nil{return nil,err}
 enqueue("completed-conflict","default",msg.ID,7*24*time.Hour)
 enqueue("same-id-other-queue","low",msg.ID,7*24*time.Hour)
 clock.AdvanceTime(7*24*time.Hour+time.Second)
 enqueue("expired-before-janitor-conflict","default",msg.ID,7*24*time.Hour)
 if err=backend.DeleteExpiredCompletedTasks("default",100);err!=nil{return nil,err}
 enqueue("expired-after-janitor","default",msg.ID,7*24*time.Hour)
 enqueue("zero-initial","zero","immediate",0)
 zero,_,err:=backend.Dequeue("zero");if err!=nil{return nil,err};if err=backend.Done(ctx,zero);err!=nil{return nil,err}
 enqueue("zero-completed-reusable","zero","immediate",0)
 return rows,nil
}
